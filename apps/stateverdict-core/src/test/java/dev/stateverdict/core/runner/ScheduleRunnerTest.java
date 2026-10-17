package dev.stateverdict.core.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.CancelledOrderNotConfirmedRule;
import dev.stateverdict.core.rules.CaptureUniquenessRule;
import dev.stateverdict.core.schedule.Fault;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Service;
import dev.stateverdict.core.schedule.Step;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the schedule runner against two in-process stub services (order and payment), so every
 * path is exercised deterministically without Docker. The real services run in CI.
 */
class ScheduleRunnerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORDER_ID = "11111111-1111-1111-1111-111111111111";

    private static final Schedule CANCEL_RACE = new Schedule("cancel-race", List.of(
            new Step.Arm(Service.ORDER, "payment.completed", Fault.hold()),
            new Step.PlaceOrder(129_900),
            new Step.AwaitHeld(Service.ORDER, "payment.completed", 1),
            new Step.Cancel(),
            new Step.ReleaseAll(Service.ORDER)));

    /** Final order state the stub reports. */
    private volatile String finalStatus;
    private volatile boolean cancelled;
    private volatile int heldInOrderService;
    private volatile boolean completionDelivered;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private HttpServer order;
    private HttpServer payment;

    @BeforeEach
    void start() throws IOException {
        finalStatus = "CONFIRMED";
        heldInOrderService = 1;
        completionDelivered = true;
        order = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        order.createContext("/", exchange -> handle("order", exchange));
        order.start();
        payment = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        payment.createContext("/", exchange -> handle("payment", exchange));
        payment.start();
    }

    @AfterEach
    void stop() {
        for (HttpServer server : List.of(order, payment)) {
            try {
                server.stop(0);
            } catch (RuntimeException alreadyStopped) {
                // a test may have stopped it to simulate an outage
            }
        }
    }

    private void handle(String service, HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        calls.add(service + " " + method + " " + path);
        String control = "/_stateverdict/dispatch/runs/run-1";

        if (method.equals("DELETE") && path.equals(control)) {
            respond(exchange, 200, "{\"discardedMessages\":0,\"unusedInterventions\":0}");
        } else if (method.equals("POST") && path.equals(control + "/interventions")) {
            respond(exchange, 202, "");
        } else if (method.equals("GET") && path.equals(control + "/held")) {
            int held = service.equals("order") ? heldInOrderService : 0;
            respond(exchange, 200, held == 0 ? "[]" : "[{\"topic\":\"payment.completed\",\"eventKey\":\"e\"}]");
        } else if (method.equals("POST") && path.equals(control + "/held/release-all")) {
            if (service.equals("order")) {
                heldInOrderService = 0;
            }
            respond(exchange, 200, "[]");
        } else if (method.equals("GET") && path.equals(control + "/journal")) {
            boolean delivered = service.equals("order") && completionDelivered && heldInOrderService == 0;
            respond(exchange, 200, delivered ? "[{\"topic\":\"payment.completed\",\"action\":\"DELIVERED\"}]" : "[]");
        } else if (service.equals("order") && method.equals("POST") && path.equals("/orders")) {
            respond(exchange, 201, "{\"id\":\"" + ORDER_ID + "\",\"status\":\"PENDING\"}");
        } else if (service.equals("order") && method.equals("POST") && path.equals("/orders/" + ORDER_ID + "/cancel")) {
            cancelled = true;
            respond(exchange, 200, orderJson("CANCELLED"));
        } else if (service.equals("order") && method.equals("GET") && path.equals("/orders/" + ORDER_ID)) {
            respond(exchange, 200, orderJson(finalStatus));
        } else if (service.equals("payment") && method.equals("GET") && path.equals("/captures")) {
            respond(exchange, 200, "{\"orderId\":\"" + ORDER_ID + "\",\"captures\":[{\"eventId\":\"e\"}]}");
        } else {
            respond(exchange, 404, "");
        }
    }

    private String orderJson(String status) {
        return "{\"id\":\"" + ORDER_ID + "\",\"status\":\"" + status + "\",\"cancelRequestedAt\":"
                + (cancelled ? "\"2026-10-16T10:00:00Z\"" : "null") + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private RunReport run(Schedule schedule) throws InterruptedException {
        ScheduleRunner.Config config = new ScheduleRunner.Config(
                URI.create("http://localhost:" + order.getAddress().getPort()),
                URI.create("http://localhost:" + payment.getAddress().getPort()),
                Duration.ofMillis(800), Duration.ZERO);
        return new ScheduleRunner(config, List.of(new CaptureUniquenessRule(), new CancelledOrderNotConfirmedRule()), JSON)
                .run(schedule, "run-1");
    }

    @Test
    void cancelledOrderThatEndsConfirmedFailsSv003() throws Exception {
        RunReport report = run(CANCEL_RACE);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(report.ruleResults()).filteredOn(r -> r.ruleId().equals("SV-003")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(EvaluationStatus.VIOLATED));
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void cancelledOrderThatStaysCancelledPasses() throws Exception {
        finalStatus = "CANCELLED";

        assertThat(run(CANCEL_RACE).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void stepsRunInOrderAndTheCancelHappensWhileTheEventIsHeld() throws Exception {
        run(CANCEL_RACE);

        List<String> seq = calls.stream().filter(c -> c.contains("interventions") || c.contains("/held")
                || c.endsWith("/cancel") || c.endsWith("POST /orders")).toList();
        int armed = seq.indexOf("order POST /_stateverdict/dispatch/runs/run-1/interventions");
        int placed = seq.indexOf("order POST /orders");
        int sawHeld = seq.indexOf("order GET /_stateverdict/dispatch/runs/run-1/held");
        int cancel = seq.indexOf("order POST /orders/" + ORDER_ID + "/cancel");
        int release = seq.indexOf("order POST /_stateverdict/dispatch/runs/run-1/held/release-all");
        assertThat(armed).isLessThan(placed);
        assertThat(placed).isLessThan(sawHeld);
        assertThat(sawHeld).isLessThan(cancel);
        assertThat(cancel).isLessThan(release);
    }

    @Test
    void bothServicesAreResetBeforeAndAfter() throws Exception {
        run(CANCEL_RACE);

        assertThat(calls.stream().filter(c -> c.equals("order DELETE /_stateverdict/dispatch/runs/run-1"))).hasSize(2);
        assertThat(calls.stream().filter(c -> c.equals("payment DELETE /_stateverdict/dispatch/runs/run-1"))).hasSize(2);
        assertThat(calls.getFirst()).endsWith("DELETE /_stateverdict/dispatch/runs/run-1");
        assertThat(calls.getLast()).endsWith("DELETE /_stateverdict/dispatch/runs/run-1");
    }

    @Test
    void aHoldThatNeverHappensIsATimeoutAndInconclusive() throws Exception {
        heldInOrderService = 0;
        completionDelivered = false;
        finalStatus = "PENDING";

        RunReport report = run(CANCEL_RACE);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(report.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(report.notes()).anyMatch(n -> n.contains("wait until 1 payment.completed held"));
        assertThat(calls).noneMatch(c -> c.endsWith("/cancel"));
    }

    @Test
    void workflowThatNeverCompletesIsATimeout() throws Exception {
        completionDelivered = false;
        finalStatus = "CANCELLED";

        RunReport report = run(CANCEL_RACE);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(report.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void evidenceIncludesBothJournalsOrderStateAndTheCancelResponse() throws Exception {
        RunReport report = run(CANCEL_RACE);

        assertThat(report.evidence()).extracting(e -> e.id()).contains("order.created", "order.cancel", "order.state",
                "payment.captures", "dispatch.journal.order", "dispatch.journal.payment");
        assertThat(report.faultSchedule()).hasSize(5)
                .first().satisfies(step -> assertThat(step).containsEntry("type", "ARM"));
    }

    @Test
    void invalidSchedulesAreRejectedBeforeAnythingRuns() {
        Schedule invalid = new Schedule("bad", List.of(new Step.Cancel()));

        assertThatThrownBy(() -> run(invalid)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLACE_ORDER");
        assertThat(calls).isEmpty();
    }

    @Test
    void unreachableOrderServiceIsAnError() throws Exception {
        order.stop(0);

        RunReport report = run(CANCEL_RACE);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.INFRA_ERROR);
        assertThat(report.verdict()).isEqualTo(Verdict.ERROR);
    }
}
