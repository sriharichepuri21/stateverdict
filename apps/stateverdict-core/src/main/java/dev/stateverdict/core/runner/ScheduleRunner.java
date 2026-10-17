package dev.stateverdict.core.runner;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Service;
import dev.stateverdict.core.schedule.Step;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.io.IOException;
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
 * Runs a {@link Schedule} against the order and payment services and judges the committed outcome.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Reset the run in both services' dispatchers.</li>
 *   <li>Execute the steps in order. {@code AWAIT_HELD} polls until the message is parked, so the next
 *       step (typically a cancel) is guaranteed to happen <em>while</em> it is held: the interleaving
 *       is controlled, not left to timing.</li>
 *   <li>Completion: the order service's journal shows {@code payment.completed} delivered and neither
 *       service holds anything for the run, within {@code deliveryDeadline}. Otherwise TIMED_OUT.</li>
 *   <li>Settle window, then collect committed order state, captures and both journals as evidence.</li>
 *   <li>Evaluate the rules on that frozen evidence, derive the verdict, reset both services.</li>
 * </ol>
 *
 * <p>A failed control call or business call StateVerdict depends on is INFRA_ERROR. A cancel answered
 * with 409 (the order already moved on) is a normal business outcome, recorded as evidence.
 */
public final class ScheduleRunner {

    static final String ORDER_EVIDENCE = "order.state";
    static final String CANCEL_EVIDENCE = "order.cancel";
    static final String CAPTURES_EVIDENCE = "payment.captures";
    static final String ORDER_JOURNAL_EVIDENCE = "dispatch.journal.order";
    static final String PAYMENT_JOURNAL_EVIDENCE = "dispatch.journal.payment";
    static final String PAYMENT_COMPLETED = "payment.completed";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    public record Config(URI orderService, URI paymentService, Duration deliveryDeadline, Duration settleWindow) {
        public Config {
            Objects.requireNonNull(orderService, "orderService");
            Objects.requireNonNull(paymentService, "paymentService");
        }

        public static Config defaults(URI orderService, URI paymentService) {
            return new Config(orderService, paymentService, Duration.ofSeconds(20), Duration.ofSeconds(2));
        }
    }

    private final Config config;
    private final List<Rule> rules;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpJson http = new HttpJson();

    public ScheduleRunner(Config config, List<Rule> rules, JsonMapper json) {
        this(config, rules, json, Clock.systemUTC());
    }

    ScheduleRunner(Config config, List<Rule> rules, JsonMapper json, Clock clock) {
        this.config = config;
        this.rules = List.copyOf(rules);
        this.json = json;
        this.clock = clock;
    }

    public RunReport run(Schedule schedule) throws InterruptedException {
        return run(schedule, "sv-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public RunReport run(Schedule schedule, String runId) throws InterruptedException {
        List<String> problems = schedule.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid schedule '" + schedule.name() + "': " + String.join("; ", problems));
        }
        Instant startedAt = clock.instant();
        List<String> notes = new ArrayList<>();
        List<EvidenceRecord> evidence = new ArrayList<>();
        String orderId = null;

        ExecutionStatus execution;
        try {
            reset(runId, Service.ORDER);
            reset(runId, Service.PAYMENT);
            execution = ExecutionStatus.COMPLETED;
            for (Step step : schedule.steps()) {
                switch (step) {
                    case Step.Arm arm -> arm(runId, arm);
                    case Step.PlaceOrder place -> orderId = placeOrder(runId, place, evidence);
                    case Step.AwaitHeld await -> {
                        if (!awaitHeld(runId, await)) {
                            execution = ExecutionStatus.TIMED_OUT;
                            notes.add("Step '" + await.describe() + "' not reached within "
                                    + config.deliveryDeadline().toMillis() + " ms.");
                        }
                    }
                    case Step.Cancel ignored -> cancel(orderId, evidence);
                    case Step.ReleaseAll release -> releaseAll(runId, release.service());
                }
                if (execution != ExecutionStatus.COMPLETED) {
                    break;
                }
            }
            if (execution == ExecutionStatus.COMPLETED && !awaitCompletion(runId)) {
                execution = ExecutionStatus.TIMED_OUT;
                notes.add("Workflow not complete within " + config.deliveryDeadline().toMillis()
                        + " ms: payment.completed not delivered to the order service, or messages still held.");
            }
            sleep(config.settleWindow());
        } catch (InfraException e) {
            execution = ExecutionStatus.INFRA_ERROR;
            notes.add(e.getMessage());
        }

        if (orderId != null) {
            collect(ORDER_EVIDENCE, config.orderService().resolve("/orders/" + encode(orderId)), evidence, notes);
            collect(CAPTURES_EVIDENCE, config.paymentService().resolve("/captures?orderId=" + encode(orderId)),
                    evidence, notes);
        }
        collect(ORDER_JOURNAL_EVIDENCE, controlUri(Service.ORDER, runId, "/journal"), evidence, notes);
        collect(PAYMENT_JOURNAL_EVIDENCE, controlUri(Service.PAYMENT, runId, "/journal"), evidence, notes);

        EvidenceBundle bundle = new EvidenceBundle(runId, evidence);
        List<RuleResult> results = rules.stream().map(rule -> rule.evaluate(bundle)).toList();
        Verdict verdict = Verdict.derive(execution, results.stream().map(RuleResult::status).toList());

        cleanUp(runId, notes);
        return new RunReport(RunReport.VERSION, runId, schedule.name(), 1, describe(schedule), execution, results,
                verdict, evidence, notes, startedAt, clock.instant());
    }

    /** The steps as plain maps for the report, with a readable description of each. */
    private List<Map<String, Object>> describe(Schedule schedule) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step step : schedule.steps()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", step.describe());
            entry.putAll(json.convertValue(step, Map.class));
            steps.add(entry);
        }
        return steps;
    }

    private void arm(String runId, Step.Arm arm) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", arm.fault().type());
        body.put("topic", arm.topic());
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

    private String placeOrder(String runId, Step.PlaceOrder place, List<EvidenceRecord> evidence)
            throws InterruptedException {
        String body = "{\"amountCents\":" + place.amountCents() + "}";
        HttpJson.Response created = call("place order",
                () -> http.post(config.orderService().resolve("/orders"), body, Map.of("X-Correlation-Id", runId)));
        requireOk("place order", created);
        JsonNode order = parse("place order", created.body());
        String orderId = order.path("id").asString("");
        if (orderId.isEmpty()) {
            throw new InfraException("place order: response has no order id");
        }
        evidence.add(EvidenceRecord.of("order.created", "order-service POST /orders", clock.instant(), created.body(), order));
        return orderId;
    }

    private boolean awaitHeld(String runId, Step.AwaitHeld await) throws InterruptedException {
        Instant deadline = clock.instant().plus(config.deliveryDeadline());
        while (true) {
            try {
                HttpJson.Response held = http.get(controlUri(await.service(), runId, "/held"));
                if (held.ok() && count(json.readTree(held.body()), "topic", await.topic()) >= await.count()) {
                    return true;
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

    private void cancel(String orderId, List<EvidenceRecord> evidence) throws InterruptedException {
        HttpJson.Response response = call("cancel order",
                () -> http.post(config.orderService().resolve("/orders/" + encode(orderId) + "/cancel"), "", Map.of()));
        if (response.status() != 200 && response.status() != 409) {
            throw new InfraException("cancel order: HTTP " + response.status());
        }
        String raw = "{\"httpStatus\":" + response.status() + ",\"order\":"
                + (response.body() == null || response.body().isBlank() ? "null" : response.body()) + "}";
        evidence.add(EvidenceRecord.of(CANCEL_EVIDENCE, "order-service POST /orders/{id}/cancel", clock.instant(), raw,
                parse("cancel order", raw)));
    }

    private void releaseAll(String runId, Service service) throws InterruptedException {
        String step = "release all held (" + service.name().toLowerCase() + ")";
        requireOk(step, call(step, () -> http.post(controlUri(service, runId, "/held/release-all"), "", Map.of())));
    }

    private boolean awaitCompletion(String runId) throws InterruptedException {
        Instant deadline = clock.instant().plus(config.deliveryDeadline());
        while (true) {
            try {
                if (completionDelivered(runId) && nothingHeld(runId, Service.ORDER) && nothingHeld(runId, Service.PAYMENT)) {
                    return true;
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

    private boolean completionDelivered(String runId) throws IOException, InterruptedException {
        HttpJson.Response journal = http.get(controlUri(Service.ORDER, runId, "/journal"));
        if (!journal.ok()) {
            return false;
        }
        JsonNode entries = json.readTree(journal.body());
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            if (PAYMENT_COMPLETED.equals(entry.path("topic").asString(""))
                    && "DELIVERED".equals(entry.path("action").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean nothingHeld(String runId, Service service) throws IOException, InterruptedException {
        HttpJson.Response held = http.get(controlUri(service, runId, "/held"));
        return held.ok() && json.readTree(held.body()).isEmpty();
    }

    private void collect(String evidenceId, URI uri, List<EvidenceRecord> evidence, List<String> notes)
            throws InterruptedException {
        try {
            HttpJson.Response response = http.get(uri);
            if (!response.ok()) {
                notes.add("Evidence " + evidenceId + " not collected: HTTP " + response.status() + " from " + uri.getPath());
                return;
            }
            String source = "GET " + uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
            evidence.add(EvidenceRecord.of(evidenceId, source, clock.instant(), response.body(),
                    json.readTree(response.body())));
        } catch (IOException | RuntimeException e) {
            notes.add("Evidence " + evidenceId + " not collected: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void reset(String runId, Service service) throws InterruptedException {
        String step = "reset run in " + service.name().toLowerCase() + " service";
        requireOk(step, call(step, () -> http.delete(controlUri(service, runId, ""))));
    }

    private void cleanUp(String runId, List<String> notes) throws InterruptedException {
        for (Service service : Service.values()) {
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

    private static long count(JsonNode array, String field, String value) {
        long count = 0;
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.get(i).path(field).asString(""))) {
                count++;
            }
        }
        return count;
    }

    private URI controlUri(Service service, String runId, String suffix) {
        URI base = service == Service.ORDER ? config.orderService() : config.paymentService();
        return base.resolve("/_stateverdict/dispatch/runs/" + encode(runId) + suffix);
    }

    private JsonNode parse(String step, String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            throw new InfraException(step + ": response is not valid JSON");
        }
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        if (!duration.isZero() && !duration.isNegative()) {
            Thread.sleep(duration.toMillis());
        }
    }

    /** StateVerdict could not drive the schedule. */
    private static final class InfraException extends RuntimeException {
        InfraException(String message) {
            super(message);
        }
    }
}
