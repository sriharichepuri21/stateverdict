package dev.stateverdict.core.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stateverdict.core.report.ReportWriter;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.CaptureUniquenessRule;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the scenario against in-process stub services, so every verdict path can be exercised
 * deterministically without Docker. The real services are exercised in CI by the Compose job.
 */
class DuplicateDeliveryScenarioTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORDER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String EVENT_ID = "22222222-2222-2222-2222-222222222222";

    /** What the stub payment service reports. */
    private volatile int deliveriesInJournal;
    private volatile int committedCaptures;
    private volatile int capturesStatus;
    private volatile int orderStatus;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String correlationIdSeen;
    private volatile String interventionBodySeen;

    private HttpServer server;
    private URI base;

    @BeforeEach
    void startStubs() throws IOException {
        deliveriesInJournal = 2;
        committedCaptures = 1;
        capturesStatus = 200;
        orderStatus = 201;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStubs() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (method.equals("POST") && path.equals("/orders")) {
            correlationIdSeen = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            respond(exchange, orderStatus, "{\"id\":\"" + ORDER_ID + "\",\"status\":\"PENDING\",\"amountCents\":129900}");
        } else if (method.equals("POST") && path.endsWith("/interventions")) {
            interventionBodySeen = body;
            respond(exchange, 202, "");
        } else if (method.equals("DELETE") && path.startsWith("/_stateverdict/dispatch/runs/")) {
            respond(exchange, 200, "{\"discardedMessages\":0,\"unusedInterventions\":0}");
        } else if (method.equals("GET") && path.endsWith("/journal")) {
            StringBuilder journal = new StringBuilder("[");
            for (int i = 1; i <= deliveriesInJournal; i++) {
                journal.append(i == 1 ? "" : ",").append("{\"action\":\"DELIVERED\",\"eventKey\":\"")
                        .append(EVENT_ID).append("\",\"attempt\":").append(i).append(",\"outcome\":\"ACKNOWLEDGED\"}");
            }
            respond(exchange, 200, journal.append("]").toString());
        } else if (method.equals("GET") && path.equals("/captures")) {
            StringBuilder captures = new StringBuilder("[");
            for (int i = 0; i < committedCaptures; i++) {
                captures.append(i == 0 ? "" : ",").append("{\"eventId\":\"").append(EVENT_ID).append("\"}");
            }
            respond(exchange, capturesStatus, "{\"orderId\":\"" + ORDER_ID + "\",\"count\":" + committedCaptures
                    + ",\"captures\":" + captures.append("]") + "}");
        } else {
            respond(exchange, 404, "");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private RunReport run(int copies) throws InterruptedException {
        return run(copies, base);
    }

    private RunReport run(int copies, URI orderService) throws InterruptedException {
        DuplicateDeliveryScenario.Config config = new DuplicateDeliveryScenario.Config(
                orderService, base, copies, 129_900, Duration.ofMillis(800), Duration.ZERO);
        return new DuplicateDeliveryScenario(config, List.of(new CaptureUniquenessRule()), JSON).run("run-test");
    }

    @Test
    void duplicateDeliveryWithTwoCommittedCapturesIsAFail() throws Exception {
        committedCaptures = 2;

        RunReport report = run(2);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(report.ruleResults()).singleElement()
                .satisfies(rule -> assertThat(rule.status()).isEqualTo(EvaluationStatus.VIOLATED));
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void duplicateDeliveryWithOneCommittedCaptureIsAPass() throws Exception {
        RunReport report = run(2);

        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(report.faultSchedule()).singleElement()
                .satisfies(fault -> assertThat(fault).containsEntry("type", "DUPLICATE").containsEntry("copies", 2));
    }

    @Test
    void theRunIsTaggedAndTheInterventionIsArmedBeforeTheOrderIsPlaced() throws Exception {
        run(3);

        assertThat(correlationIdSeen).isEqualTo("run-test");
        assertThat(interventionBodySeen).contains("\"copies\":3");
        assertThat(requests.indexOf("POST /_stateverdict/dispatch/runs/run-test/interventions"))
                .isLessThan(requests.indexOf("POST /orders"));
        assertThat(requests.getFirst()).isEqualTo("DELETE /_stateverdict/dispatch/runs/run-test");
        assertThat(requests.getLast()).isEqualTo("DELETE /_stateverdict/dispatch/runs/run-test");
    }

    @Test
    void oneCopyArmsNoFaultAndIsTheBaseline() throws Exception {
        deliveriesInJournal = 1;

        RunReport report = run(1);

        assertThat(report.faultSchedule()).isEmpty();
        assertThat(interventionBodySeen).isNull();
        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void missingDeliveriesAtTheDeadlineAreInconclusive() throws Exception {
        deliveriesInJournal = 1;

        RunReport report = run(2);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(report.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(report.notes()).anyMatch(note -> note.contains("Expected 2 deliveries"));
    }

    @Test
    void aViolationObservedAfterATimeoutIsStillAFail() throws Exception {
        deliveriesInJournal = 1;
        committedCaptures = 2;

        RunReport report = run(2);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void unreadableCommittedStateIsInconclusiveNotAPass() throws Exception {
        capturesStatus = 500;

        RunReport report = run(2);

        assertThat(report.ruleResults().getFirst().status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(report.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(report.notes()).anyMatch(note -> note.contains("payment.captures not collected"));
    }

    @Test
    void failingToPlaceTheOrderIsAnError() throws Exception {
        orderStatus = 503;

        RunReport report = run(2);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.INFRA_ERROR);
        assertThat(report.verdict()).isEqualTo(Verdict.ERROR);
    }

    @Test
    void unreachableOrderServiceIsAnError() throws Exception {
        RunReport report = run(2, URI.create("http://localhost:1"));

        assertThat(report.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(report.notes()).anyMatch(note -> note.contains("place order"));
    }

    @Test
    void evidenceCarriesProvenance() throws Exception {
        committedCaptures = 2;

        RunReport report = run(2);

        assertThat(report.evidence()).extracting(e -> e.id())
                .contains("order.created", "payment.captures", "dispatch.journal");
        assertThat(report.evidence()).allSatisfy(e -> {
            assertThat(e.sha256()).hasSize(64);
            assertThat(e.observedAt()).isNotNull();
            assertThat(e.source()).isNotBlank();
        });
    }

    @Test
    void savedReportReplaysToTheSameVerdictWithoutContactingServices() throws Exception {
        committedCaptures = 2;
        ReportWriter writer = new ReportWriter(JSON);
        RunReport original = run(2);
        String saved = writer.toJson(original);
        int requestsBeforeReplay = requests.size();

        RunReport replayed = DuplicateDeliveryScenario.replay(
                writer.fromJson(saved), List.of(new CaptureUniquenessRule()), Clock.systemUTC());

        assertThat(replayed.verdict()).isEqualTo(original.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(replayed.ruleResults()).isEqualTo(original.ruleResults());
        assertThat(replayed.notes()).anyMatch(note -> note.contains("Replayed from saved evidence"));
        assertThat(requests).hasSize(requestsBeforeReplay);
    }
}
