package dev.stateverdict.core.saga;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.runner.HttpJson;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link SagaSchedule} against the external saga target and judges the committed outcome.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Choose an order id; the run id is {@code order-<id>}, which is how the adapter tags every
 *       message about that order (the target carries no correlation header of its own).</li>
 *   <li>Reset the run in the payment and stock dispatchers, create a fresh customer and product with
 *       the schedule's starting balances, and arm the faults.</li>
 *   <li>Place the order, then wait until the order service has decided it and nothing is still
 *       delayed, within {@code decisionDeadline}. An order that is never decided is not a timeout:
 *       it is business evidence, judged by SAGA-1.</li>
 *   <li>Settle window, then collect the order, customer, product and both journals as evidence and
 *       evaluate. While an eventual rule is not satisfied, re-observe for up to {@code eventualWindow}.</li>
 *   <li>Derive the verdict and reset both dispatchers.</li>
 * </ol>
 */
public final class SagaRunner {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration OBSERVE_INTERVAL = Duration.ofMillis(250);
    private static final long MIN_ORDER_ID = 1_000_000_000_000L;

    public record Config(URI orderService, URI paymentService, URI stockService, Duration decisionDeadline,
                         Duration settleWindow, Duration eventualWindow) {
        public Config {
            Objects.requireNonNull(orderService, "orderService");
            Objects.requireNonNull(paymentService, "paymentService");
            Objects.requireNonNull(stockService, "stockService");
        }

        public static Config defaults(URI orderService, URI paymentService, URI stockService) {
            return new Config(orderService, paymentService, stockService, Duration.ofSeconds(25),
                    Duration.ofSeconds(1), Duration.ofSeconds(5));
        }
    }

    public static List<Rule> rules() {
        return List.of(new OrderDecidedRule(), SettlementRule.money(), SettlementRule.stock());
    }

    private final Config config;
    private final List<Rule> rules;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpJson http = new HttpJson();

    public SagaRunner(Config config, List<Rule> rules, JsonMapper json) {
        this(config, rules, json, Clock.systemUTC());
    }

    SagaRunner(Config config, List<Rule> rules, JsonMapper json, Clock clock) {
        this.config = config;
        this.rules = List.copyOf(rules);
        this.json = json;
        this.clock = clock;
    }

    public RunReport run(SagaSchedule schedule) throws InterruptedException {
        return run(schedule, ThreadLocalRandom.current().nextLong(MIN_ORDER_ID, MIN_ORDER_ID * 1000));
    }

    RunReport run(SagaSchedule schedule, long orderId) throws InterruptedException {
        List<String> problems = schedule.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid schedule '" + schedule.name() + "': " + String.join("; ", problems));
        }
        String runId = "order-" + orderId;
        Instant startedAt = clock.instant();
        List<String> notes = new ArrayList<>();
        List<EvidenceRecord> setupEvidence = new ArrayList<>();
        boolean placed = false;

        ExecutionStatus execution = ExecutionStatus.COMPLETED;
        try {
            reset(runId, SagaSchedule.Service.PAYMENT);
            reset(runId, SagaSchedule.Service.STOCK);
            SagaSchedule.Order order = schedule.order();
            long customerId = create(config.paymentService(), "customers", "amountAvailable", order.funds());
            long productId = create(config.stockService(), "products", "availableItems", order.stock());
            for (SagaSchedule.Arm arm : schedule.arms()) {
                arm(runId, arm);
            }
            Map<String, Object> setup = new LinkedHashMap<>();
            setup.put("orderId", orderId);
            setup.put("customerId", customerId);
            setup.put("productId", productId);
            setup.put("price", order.price());
            setup.put("count", order.count());
            setup.put("funds", order.funds());
            setup.put("stock", order.stock());
            String setupJson = json.writeValueAsString(setup);
            setupEvidence.add(EvidenceRecord.of(SagaEvidence.SETUP, "StateVerdict run setup", clock.instant(), setupJson,
                    json.readTree(setupJson)));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", orderId);
            body.put("customerId", customerId);
            body.put("productId", productId);
            body.put("productCount", order.count());
            body.put("price", order.price());
            body.put("status", "NEW");
            requireOk("place order", call("place order",
                    () -> http.post(config.orderService().resolve("/orders"), json.writeValueAsString(body), Map.of())));
            placed = true;

            if (!awaitDecidedAndQuiet(orderId, runId)) {
                notes.add("Within " + config.decisionDeadline().toMillis() + " ms the order was not decided, or a "
                        + "delayed message was still pending; judged on the state observed after that.");
            }
            sleep(config.settleWindow());
        } catch (InfraException e) {
            execution = ExecutionStatus.INFRA_ERROR;
            notes.add(e.getMessage());
        }

        List<EvidenceRecord> evidence = List.copyOf(setupEvidence);
        List<RuleResult> results = List.of();
        if (placed) {
            Instant windowEnd = clock.instant().plus(config.eventualWindow());
            List<String> collectionNotes = new ArrayList<>();
            evidence = observe(runId, orderId, setupEvidence, collectionNotes);
            results = evaluate(runId, evidence);
            int observations = 1;
            while (pendingEventual(results) && clock.instant().isBefore(windowEnd)) {
                sleep(OBSERVE_INTERVAL);
                collectionNotes = new ArrayList<>();
                evidence = observe(runId, orderId, setupEvidence, collectionNotes);
                results = evaluate(runId, evidence);
                observations++;
            }
            notes.addAll(collectionNotes);
            if (observations > 1) {
                notes.add("Eventual rules re-observed " + observations + " times within the "
                        + config.eventualWindow().toMillis() + " ms window"
                        + (pendingEventual(results) ? "; still not satisfied at the end." : "."));
            }
        }
        Verdict verdict = Verdict.derive(execution, results.stream().map(RuleResult::status).toList());
        cleanUp(runId, notes);
        return new RunReport(RunReport.VERSION, runId, schedule.name(), 1, describe(schedule), execution, results,
                verdict, evidence, notes, startedAt, clock.instant());
    }

    private List<EvidenceRecord> observe(String runId, long orderId, List<EvidenceRecord> setupEvidence,
                                         List<String> notes) throws InterruptedException {
        List<EvidenceRecord> evidence = new ArrayList<>(setupEvidence);
        JsonNode setup = setupEvidence.getFirst().body();
        collectOrder(orderId, evidence, notes);
        collect(SagaEvidence.CUSTOMER, config.paymentService().resolve("/_stateverdict/customers/"
                + SagaEvidence.number(setup.path("customerId"))), evidence, notes);
        collect(SagaEvidence.PRODUCT, config.stockService().resolve("/_stateverdict/products/"
                + SagaEvidence.number(setup.path("productId"))), evidence, notes);
        collect(SagaEvidence.PAYMENT_JOURNAL, controlUri(SagaSchedule.Service.PAYMENT, runId, "/journal"), evidence, notes);
        collect(SagaEvidence.STOCK_JOURNAL, controlUri(SagaSchedule.Service.STOCK, runId, "/journal"), evidence, notes);
        return evidence;
    }

    /** The order service lists every order in its state store; keep only this run's. */
    private void collectOrder(long orderId, List<EvidenceRecord> evidence, List<String> notes)
            throws InterruptedException {
        try {
            HttpJson.Response response = http.get(config.orderService().resolve("/orders"));
            if (!response.ok()) {
                notes.add("Evidence " + SagaEvidence.ORDER + " not collected: HTTP " + response.status());
                return;
            }
            JsonNode match = find(json.readTree(response.body()), orderId);
            String raw = match == null ? "{\"id\":" + orderId + ",\"found\":false}" : json.writeValueAsString(match);
            evidence.add(EvidenceRecord.of(SagaEvidence.ORDER, "GET /orders (entry " + orderId + ")", clock.instant(), raw,
                    json.readTree(raw)));
        } catch (IOException | RuntimeException e) {
            notes.add("Evidence " + SagaEvidence.ORDER + " not collected: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JsonNode find(JsonNode orders, long orderId) {
        for (int i = 0; i < orders.size(); i++) {
            Long id = SagaEvidence.number(orders.get(i).path("id"));
            if (id != null && id == orderId) {
                return orders.get(i);
            }
        }
        return null;
    }

    private List<RuleResult> evaluate(String runId, List<EvidenceRecord> evidence) {
        EvidenceBundle bundle = new EvidenceBundle(runId, evidence);
        return rules.stream().map(rule -> rule.evaluate(bundle)).toList();
    }

    private boolean pendingEventual(List<RuleResult> results) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).eventual() && results.get(i).status() != EvaluationStatus.SATISFIED) {
                return true;
            }
        }
        return false;
    }

    private boolean awaitDecidedAndQuiet(long orderId, String runId) throws InterruptedException {
        Instant deadline = clock.instant().plus(config.decisionDeadline());
        while (true) {
            try {
                HttpJson.Response orders = http.get(config.orderService().resolve("/orders"));
                if (orders.ok()) {
                    JsonNode order = find(json.readTree(orders.body()), orderId);
                    if (order != null && SagaEvidence.decided(order.path("status").asString(""))
                            && nothingHeld(runId, SagaSchedule.Service.PAYMENT) && nothingHeld(runId, SagaSchedule.Service.STOCK)) {
                        return true;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // keep polling until the deadline
            }
            if (!clock.instant().isBefore(deadline)) {
                return false;
            }
            sleep(POLL_INTERVAL);
        }
    }

    private boolean nothingHeld(String runId, SagaSchedule.Service service) throws IOException, InterruptedException {
        HttpJson.Response held = http.get(controlUri(service, runId, "/held"));
        return held.ok() && json.readTree(held.body()).isEmpty();
    }

    private long create(URI service, String kind, String field, int amount) throws InterruptedException {
        String step = "create " + kind;
        HttpJson.Response created = call(step, () -> http.post(service.resolve("/_stateverdict/" + kind),
                "{\"" + field + "\":" + amount + "}", Map.of()));
        requireOk(step, created);
        Long id;
        try {
            id = SagaEvidence.number(json.readTree(created.body()).path("id"));
        } catch (RuntimeException e) {
            id = null;
        }
        if (id == null) {
            throw new InfraException(step + ": response has no id");
        }
        return id;
    }

    private void arm(String runId, SagaSchedule.Arm arm) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", arm.fault().type());
        body.put("topic", arm.phase());
        if (arm.fault().copies() != null) {
            body.put("copies", arm.fault().copies());
        }
        if (arm.fault().delayMs() != null) {
            body.put("delayMs", arm.fault().delayMs());
        }
        String step = arm.describe();
        requireOk(step, call(step, () -> http.post(controlUri(arm.service(), runId, "/interventions"),
                json.writeValueAsString(body), Map.of())));
    }

    private List<Map<String, Object>> describe(SagaSchedule schedule) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (SagaSchedule.Arm arm : schedule.arms()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", arm.describe());
            entry.putAll(json.convertValue(arm, Map.class));
            steps.add(entry);
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("step", "place order: price " + schedule.order().price() + " (funds " + schedule.order().funds()
                + "), count " + schedule.order().count() + " (stock " + schedule.order().stock() + ")");
        order.putAll(json.convertValue(schedule.order(), Map.class));
        steps.add(order);
        return steps;
    }

    private void collect(String evidenceId, URI uri, List<EvidenceRecord> evidence, List<String> notes)
            throws InterruptedException {
        try {
            HttpJson.Response response = http.get(uri);
            if (!response.ok()) {
                notes.add("Evidence " + evidenceId + " not collected: HTTP " + response.status() + " from " + uri.getPath());
                return;
            }
            evidence.add(EvidenceRecord.of(evidenceId, "GET " + uri.getPath(), clock.instant(), response.body(),
                    json.readTree(response.body())));
        } catch (IOException | RuntimeException e) {
            notes.add("Evidence " + evidenceId + " not collected: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void reset(String runId, SagaSchedule.Service service) throws InterruptedException {
        String step = "reset run in " + service.name().toLowerCase() + " service";
        requireOk(step, call(step, () -> http.delete(controlUri(service, runId, ""))));
    }

    private void cleanUp(String runId, List<String> notes) throws InterruptedException {
        for (SagaSchedule.Service service : SagaSchedule.Service.values()) {
            try {
                HttpJson.Response reset = http.delete(controlUri(service, runId, ""));
                if (!reset.ok()) {
                    notes.add("Cleanup: reset in " + service + " returned HTTP " + reset.status() + " (verdict unaffected).");
                }
            } catch (IOException e) {
                notes.add("Cleanup: reset in " + service + " failed: " + e.getMessage() + " (verdict unaffected).");
            }
        }
    }

    private URI controlUri(SagaSchedule.Service service, String runId, String suffix) {
        URI base = service == SagaSchedule.Service.PAYMENT ? config.paymentService() : config.stockService();
        return base.resolve("/_stateverdict/dispatch/runs/" + runId + suffix);
    }

    private interface HttpCall {
        HttpJson.Response call() throws IOException, InterruptedException;
    }

    private static HttpJson.Response call(String step, HttpCall call) throws InterruptedException {
        try {
            return call.call();
        } catch (IOException e) {
            throw new InfraException(step + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void requireOk(String step, HttpJson.Response response) {
        if (!response.ok()) {
            throw new InfraException(step + ": HTTP " + response.status());
        }
    }

    private static void sleep(Duration duration) throws InterruptedException {
        if (!duration.isZero() && !duration.isNegative()) {
            Thread.sleep(duration.toMillis());
        }
    }

    /** StateVerdict could not drive the run. */
    private static final class InfraException extends RuntimeException {
        InfraException(String message) {
            super(message);
        }
    }
}
