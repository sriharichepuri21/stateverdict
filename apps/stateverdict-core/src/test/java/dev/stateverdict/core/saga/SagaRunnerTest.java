package dev.stateverdict.core.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Fault;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the saga runner against three in-process stub services (order, payment, stock), so every
 * path is exercised without Docker. The real target runs in CI.
 */
class SagaRunnerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long ORDER_ID = 4_242_424_242_424L;
    private static final String RUN = "/_stateverdict/dispatch/runs/order-" + ORDER_ID;
    private static final SagaSchedule PLAIN = new SagaSchedule("plain", new SagaSchedule.Order(100, 1, 1000, 10), List.of());

    private volatile String status;
    private volatile String customer;
    private volatile String product;
    /** Customer lookups answered with "still reserved" before the stub settles. */
    private volatile int unsettledLookups;
    private final AtomicInteger customerLookups = new AtomicInteger();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private HttpServer order;
    private HttpServer payment;
    private HttpServer stock;

    @BeforeEach
    void start() throws IOException {
        status = "CONFIRMED";
        customer = "{\"id\":7,\"amountAvailable\":900,\"amountReserved\":0}";
        product = "{\"id\":9,\"availableItems\":9,\"reservedItems\":0}";
        order = server("order");
        payment = server("payment");
        stock = server("stock");
    }

    private HttpServer server(String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> handle(name, exchange));
        server.start();
        return server;
    }

    @AfterEach
    void stop() {
        for (HttpServer server : List.of(order, payment, stock)) {
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
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(service + " " + method + " " + path);
        if (!body.isEmpty()) {
            bodies.add(service + " " + path + " " + body);
        }
        if (method.equals("DELETE") && path.equals(RUN)) {
            respond(exchange, 200, "{}");
        } else if (method.equals("POST") && path.equals(RUN + "/interventions")) {
            respond(exchange, 202, "");
        } else if (method.equals("GET") && (path.equals(RUN + "/held"))) {
            respond(exchange, 200, "[]");
        } else if (method.equals("GET") && path.equals(RUN + "/journal")) {
            respond(exchange, 200, "[]");
        } else if (service.equals("payment") && method.equals("POST") && path.equals("/_stateverdict/customers")) {
            respond(exchange, 200, "{\"id\":7,\"amountAvailable\":1000,\"amountReserved\":0}");
        } else if (service.equals("stock") && method.equals("POST") && path.equals("/_stateverdict/products")) {
            respond(exchange, 200, "{\"id\":9,\"availableItems\":10,\"reservedItems\":0}");
        } else if (service.equals("payment") && method.equals("GET") && path.equals("/_stateverdict/customers/7")) {
            boolean settled = customerLookups.incrementAndGet() > unsettledLookups;
            respond(exchange, 200, settled ? customer : "{\"id\":7,\"amountAvailable\":900,\"amountReserved\":100}");
        } else if (service.equals("stock") && method.equals("GET") && path.equals("/_stateverdict/products/9")) {
            respond(exchange, 200, product);
        } else if (service.equals("order") && method.equals("POST") && path.equals("/orders")) {
            respond(exchange, 200, body);
        } else if (service.equals("order") && method.equals("GET") && path.equals("/orders")) {
            respond(exchange, 200, "[{\"id\":1,\"status\":\"CONFIRMED\"},{\"id\":" + ORDER_ID + ",\"status\":\"" + status + "\"}]");
        } else {
            respond(exchange, 404, "");
        }
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private SagaRunner runner(Duration decisionDeadline, Duration eventualWindow) {
        URI o = URI.create("http://localhost:" + order.getAddress().getPort());
        URI p = URI.create("http://localhost:" + payment.getAddress().getPort());
        URI s = URI.create("http://localhost:" + stock.getAddress().getPort());
        return new SagaRunner(new SagaRunner.Config(o, p, s, decisionDeadline, Duration.ZERO, eventualWindow),
                SagaRunner.rules(), JSON);
    }

    private static EvaluationStatus status(RunReport report, String ruleId) {
        return report.ruleResults().stream().filter(r -> r.ruleId().equals(ruleId)).map(RuleResult::status)
                .findFirst().orElseThrow();
    }

    @Test
    void aCorrectlySettledOrderPassesAndTheRunIsSetUpAndCleanedUp() throws Exception {
        RunReport report = runner(Duration.ofSeconds(2), Duration.ofSeconds(1)).run(PLAIN, ORDER_ID);

        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(report.runId()).isEqualTo("order-" + ORDER_ID);
        assertThat(bodies).anyMatch(b -> b.startsWith("payment /_stateverdict/customers") && b.contains("\"amountAvailable\":1000"));
        assertThat(bodies).anyMatch(b -> b.startsWith("stock /_stateverdict/products") && b.contains("\"availableItems\":10"));
        assertThat(bodies).anyMatch(b -> b.startsWith("order /orders") && b.contains("\"id\":" + ORDER_ID)
                && b.contains("\"customerId\":7") && b.contains("\"productId\":9") && b.contains("\"status\":\"NEW\""));
        assertThat(calls.stream().filter(c -> c.endsWith("DELETE " + RUN)).count()).isEqualTo(4);
        assertThat(report.evidence()).extracting(e -> e.id()).contains(SagaEvidence.SETUP, SagaEvidence.ORDER,
                SagaEvidence.CUSTOMER, SagaEvidence.PRODUCT, SagaEvidence.PAYMENT_JOURNAL, SagaEvidence.STOCK_JOURNAL);
    }

    @Test
    void faultsAreArmedOnTheServiceAndPhaseTheyName() throws Exception {
        SagaSchedule schedule = PLAIN.withArms("dup", List.of(
                new SagaSchedule.Arm(SagaSchedule.Service.STOCK, SagaSchedule.SETTLE, Fault.duplicateConcurrent(2))));

        runner(Duration.ofSeconds(2), Duration.ofSeconds(1)).run(schedule, ORDER_ID);

        assertThat(bodies).anyMatch(b -> b.startsWith("stock " + RUN + "/interventions")
                && b.contains("\"type\":\"DUPLICATE_CONCURRENT\"") && b.contains("\"topic\":\"orders.settle\""));
        assertThat(bodies).noneMatch(b -> b.startsWith("payment " + RUN + "/interventions"));
    }

    @Test
    void moneyReturnedThatWasNeverReservedFailsSaga2() throws Exception {
        status = "ROLLBACK";
        customer = "{\"id\":7,\"amountAvailable\":1100,\"amountReserved\":-100}";
        product = "{\"id\":9,\"availableItems\":10,\"reservedItems\":0}";

        RunReport report = runner(Duration.ofSeconds(2), Duration.ofMillis(500)).run(PLAIN, ORDER_ID);

        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(status(report, SettlementRule.MONEY_ID)).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(status(report, SettlementRule.STOCK_ID)).isEqualTo(EvaluationStatus.SATISFIED);
        assertThat(status(report, OrderDecidedRule.ID)).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void aSettlementThatArrivesWithinTheWindowPasses() throws Exception {
        unsettledLookups = 3;

        RunReport report = runner(Duration.ofSeconds(2), Duration.ofSeconds(3)).run(PLAIN, ORDER_ID);

        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(report.notes()).anyMatch(n -> n.startsWith("Eventual rules re-observed"));
    }

    @Test
    void anOrderThatIsNeverDecidedFailsSaga1AndIsNotATimeout() throws Exception {
        status = "NEW";

        RunReport report = runner(Duration.ofMillis(600), Duration.ofMillis(300)).run(PLAIN, ORDER_ID);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(status(report, OrderDecidedRule.ID)).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(status(report, SettlementRule.MONEY_ID)).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(report.notes()).anyMatch(n -> n.contains("was not decided"));
    }

    @Test
    void anUnreachableServiceIsAnErrorNotAPass() throws Exception {
        stock.stop(0);

        RunReport report = runner(Duration.ofSeconds(1), Duration.ofMillis(200)).run(PLAIN, ORDER_ID);

        assertThat(report.executionStatus()).isEqualTo(ExecutionStatus.INFRA_ERROR);
        assertThat(report.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(calls).noneMatch(c -> c.startsWith("order POST /orders"));
    }

    @Test
    void anInvalidScheduleIsRejectedBeforeAnythingRuns() {
        SagaSchedule invalid = PLAIN.withArms("bad", List.of(
                new SagaSchedule.Arm(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE, Fault.hold())));

        assertThatThrownBy(() -> runner(Duration.ofSeconds(1), Duration.ZERO).run(invalid, ORDER_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}
