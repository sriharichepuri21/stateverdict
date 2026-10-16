package dev.stateverdict.core.runner;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario {@value #NAME}: place one order while the payment request is delivered {@code copies}
 * times, then judge the committed outcome with the given rules.
 *
 * <h2>Lifecycle (spec figure 2)</h2>
 * <ol>
 *   <li>Reset the run in the payment service's dispatcher, then arm the duplicate intervention.</li>
 *   <li>Trigger: {@code POST /orders} with {@code X-Correlation-Id: <runId>}.</li>
 *   <li>Wait, up to {@code deliveryDeadline}, for the dispatcher journal to show every expected
 *       delivery. The evaluation point is defined by delivered events, not by elapsed time.</li>
 *   <li>Wait {@code settleWindow} more, so a late side effect can still appear.</li>
 *   <li>Collect committed state ({@code GET /captures}) and the journal as evidence.</li>
 *   <li>Evaluate rules on that frozen evidence, derive the verdict, reset the run.</li>
 * </ol>
 *
 * <p>Failures in steps 1–2 mean StateVerdict could not drive the scenario: {@code INFRA_ERROR}.
 * Missing deliveries in step 3 mean {@code TIMED_OUT}. Failed evidence reads in step 5 leave the
 * evidence missing, so rules return {@code UNKNOWN}; they never default to a pass.
 */
public final class DuplicateDeliveryScenario {

    public static final String NAME = "checkout-duplicate-payment-request";
    public static final int VERSION = 1;

    static final String ORDER_EVIDENCE = "order.created";
    static final String CAPTURES_EVIDENCE = "payment.captures";
    static final String JOURNAL_EVIDENCE = "dispatch.journal";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    public record Config(
            URI orderService,
            URI paymentService,
            int copies,
            long amountCents,
            Duration deliveryDeadline,
            Duration settleWindow) {

        public Config {
            Objects.requireNonNull(orderService, "orderService");
            Objects.requireNonNull(paymentService, "paymentService");
            if (copies < 1) {
                throw new IllegalArgumentException("copies must be at least 1");
            }
            if (amountCents <= 0) {
                throw new IllegalArgumentException("amountCents must be positive");
            }
        }

        public static Config defaults(URI orderService, URI paymentService) {
            return new Config(orderService, paymentService, 2, 129_900, Duration.ofSeconds(20), Duration.ofSeconds(2));
        }
    }

    private final Config config;
    private final List<Rule> rules;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpJson http = new HttpJson();

    public DuplicateDeliveryScenario(Config config, List<Rule> rules, JsonMapper json) {
        this(config, rules, json, Clock.systemUTC());
    }

    DuplicateDeliveryScenario(Config config, List<Rule> rules, JsonMapper json, Clock clock) {
        this.config = config;
        this.rules = List.copyOf(rules);
        this.json = json;
        this.clock = clock;
    }

    public RunReport run() throws InterruptedException {
        return run("sv-" + UUID.randomUUID().toString().substring(0, 8));
    }

    RunReport run(String runId) throws InterruptedException {
        Instant startedAt = clock.instant();
        List<String> notes = new ArrayList<>();
        List<EvidenceRecord> evidence = new ArrayList<>();
        List<Map<String, Object>> faultSchedule = new ArrayList<>();
        int expectedDeliveries = config.copies();

        ExecutionStatus execution;
        try {
            prepare(runId, faultSchedule);
            String orderId = trigger(runId, evidence);
            execution = awaitDeliveries(runId, expectedDeliveries, notes);
            sleep(config.settleWindow());
            collect(CAPTURES_EVIDENCE, paymentUri("/captures?orderId=" + encode(orderId)), evidence, notes);
        } catch (InfraException e) {
            execution = ExecutionStatus.INFRA_ERROR;
            notes.add(e.getMessage());
        }
        collect(JOURNAL_EVIDENCE, controlUri(runId, "/journal"), evidence, notes);

        EvidenceBundle bundle = new EvidenceBundle(runId, evidence);
        List<RuleResult> results = rules.stream().map(rule -> rule.evaluate(bundle)).toList();
        Verdict verdict = Verdict.derive(execution, results.stream().map(RuleResult::status).toList());

        cleanUp(runId, notes);
        return new RunReport(RunReport.VERSION, runId, NAME, VERSION, faultSchedule, execution, results, verdict,
                evidence, notes, startedAt, clock.instant());
    }

    /** Re-derives a verdict from a saved report's evidence without touching any service. */
    public static RunReport replay(RunReport saved, List<Rule> rules, Clock clock) {
        EvidenceBundle bundle = new EvidenceBundle(saved.runId(), saved.evidence());
        List<RuleResult> results = rules.stream().map(rule -> rule.evaluate(bundle)).toList();
        List<EvaluationStatus> statuses = results.stream().map(RuleResult::status).toList();
        List<String> notes = new ArrayList<>(saved.notes());
        notes.add("Replayed from saved evidence at " + clock.instant() + "; no service was contacted.");
        return new RunReport(RunReport.VERSION, saved.runId(), saved.scenario(), saved.scenarioVersion(),
                saved.faultSchedule(), saved.executionStatus(), results,
                Verdict.derive(saved.executionStatus(), statuses), saved.evidence(), notes,
                saved.startedAt(), saved.completedAt());
    }

    private void prepare(String runId, List<Map<String, Object>> faultSchedule) throws InterruptedException {
        HttpJson.Response reset = call("reset run " + runId, () -> http.delete(controlUri(runId, "")));
        requireOk("reset run " + runId, reset);
        if (config.copies() > 1) {
            String body = "{\"type\":\"DUPLICATE\",\"topic\":\"payment.requested\",\"copies\":" + config.copies() + "}";
            HttpJson.Response armed = call("arm duplicate intervention",
                    () -> http.post(controlUri(runId, "/interventions"), body, Map.of()));
            requireOk("arm duplicate intervention", armed);
            Map<String, Object> fault = new LinkedHashMap<>();
            fault.put("type", "DUPLICATE");
            fault.put("topic", "payment.requested");
            fault.put("copies", config.copies());
            faultSchedule.add(fault);
        }
    }

    private String trigger(String runId, List<EvidenceRecord> evidence) throws InterruptedException {
        String body = "{\"amountCents\":" + config.amountCents() + "}";
        HttpJson.Response created = call("place order",
                () -> http.post(orderUri("/orders"), body, Map.of("X-Correlation-Id", runId)));
        requireOk("place order", created);
        JsonNode order = parse("place order", created.body());
        String orderId = order.path("id").asString("");
        if (orderId.isEmpty()) {
            throw new InfraException("place order: response has no order id");
        }
        evidence.add(EvidenceRecord.of(ORDER_EVIDENCE, "order-service POST /orders", clock.instant(), created.body(), order));
        return orderId;
    }

    private ExecutionStatus awaitDeliveries(String runId, int expected, List<String> notes) throws InterruptedException {
        Instant deadline = clock.instant().plus(config.deliveryDeadline());
        int delivered = -1;
        while (true) {
            try {
                HttpJson.Response journal = http.get(controlUri(runId, "/journal"));
                if (journal.ok()) {
                    delivered = countDeliveries(json.readTree(journal.body()));
                    if (delivered >= expected) {
                        return ExecutionStatus.COMPLETED;
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                // Keep polling until the deadline; a transient read failure is not a verdict.
            }
            if (!clock.instant().isBefore(deadline)) {
                notes.add("Expected " + expected + " deliveries within " + config.deliveryDeadline().toMillis()
                        + " ms; observed " + Math.max(delivered, 0) + ".");
                return ExecutionStatus.TIMED_OUT;
            }
            sleep(POLL_INTERVAL);
        }
    }

    private void collect(String evidenceId, URI uri, List<EvidenceRecord> evidence, List<String> notes)
            throws InterruptedException {
        try {
            HttpJson.Response response = http.get(uri);
            if (!response.ok()) {
                notes.add("Evidence " + evidenceId + " not collected: HTTP " + response.status() + " from " + uri.getPath());
                return;
            }
            evidence.add(EvidenceRecord.of(evidenceId, sourceName(uri), clock.instant(), response.body(),
                    json.readTree(response.body())));
        } catch (java.io.IOException | RuntimeException e) {
            notes.add("Evidence " + evidenceId + " not collected: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void cleanUp(String runId, List<String> notes) throws InterruptedException {
        try {
            HttpJson.Response reset = http.delete(controlUri(runId, ""));
            if (!reset.ok()) {
                notes.add("Cleanup: reset returned HTTP " + reset.status() + " (verdict unaffected).");
            }
        } catch (java.io.IOException e) {
            notes.add("Cleanup: reset failed: " + e.getMessage() + " (verdict unaffected).");
        }
    }

    private static int countDeliveries(JsonNode journal) {
        int count = 0;
        for (int i = 0; i < journal.size(); i++) {
            if ("DELIVERED".equals(journal.get(i).path("action").asString(""))) {
                count++;
            }
        }
        return count;
    }

    private JsonNode parse(String step, String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            throw new InfraException(step + ": response is not valid JSON");
        }
    }

    private interface HttpCall {
        HttpJson.Response call() throws java.io.IOException, InterruptedException;
    }

    private static HttpJson.Response call(String step, HttpCall call) throws InterruptedException {
        try {
            return call.call();
        } catch (java.io.IOException e) {
            throw new InfraException(step + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void requireOk(String step, HttpJson.Response response) {
        if (!response.ok()) {
            throw new InfraException(step + ": HTTP " + response.status());
        }
    }

    private URI orderUri(String path) {
        return config.orderService().resolve(path);
    }

    private URI paymentUri(String path) {
        return config.paymentService().resolve(path);
    }

    private URI controlUri(String runId, String suffix) {
        return paymentUri("/_stateverdict/dispatch/runs/" + encode(runId) + suffix);
    }

    private static String sourceName(URI uri) {
        return "GET " + uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        if (!duration.isZero() && !duration.isNegative()) {
            Thread.sleep(duration.toMillis());
        }
    }

    /** StateVerdict could not drive the scenario. */
    private static final class InfraException extends RuntimeException {
        InfraException(String message) {
            super(message);
        }
    }
}
