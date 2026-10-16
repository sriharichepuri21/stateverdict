package dev.stateverdict.examples.payment;

import dev.stateverdict.dispatch.ControlledDispatcher;
import dev.stateverdict.dispatch.HeldMessage;
import dev.stateverdict.dispatch.Intervention;
import dev.stateverdict.dispatch.JournalEntry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP API through which StateVerdict controls deliveries. Exists only when
 * {@code stateverdict.dispatch.mode=controlled}; in production mode these routes return 404.
 */
@RestController
@RequestMapping("/_stateverdict/dispatch/runs/{runId}")
@ConditionalOnProperty(name = DispatchConfig.MODE_PROPERTY, havingValue = "controlled")
public class DispatchControlController {

    /**
     * @param type     {@code HOLD}, {@code DUPLICATE} or {@code DELAY}
     * @param topic    defaults to {@code payment.requested}
     * @param copies   for {@code DUPLICATE}
     * @param delayMs  for {@code DELAY}
     */
    record InterventionRequest(String type, String topic, Integer copies, Long delayMs) {
    }

    record ResetResponse(String runId, int discardedMessages, int unusedInterventions) {
    }

    /** Same rule as the order service applies to X-Correlation-Id. */
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ControlledDispatcher dispatcher;

    public DispatchControlController(ControlledDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/interventions")
    public ResponseEntity<Void> schedule(@PathVariable String runId, @RequestBody InterventionRequest request) {
        requireValid(runId);
        String topic = request.topic() == null ? PaymentRequestedListener.TOPIC : request.topic();
        dispatcher.schedule(runId, topic, toIntervention(request));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/held")
    public List<HeldMessage> held(@PathVariable String runId) {
        requireValid(runId);
        return dispatcher.held(runId);
    }

    @PostMapping("/held/{token}/release")
    public ResponseEntity<JournalEntry> release(@PathVariable String runId, @PathVariable UUID token) {
        requireValid(runId);
        return ResponseEntity.of(dispatcher.release(runId, token));
    }

    @PostMapping("/held/release-all")
    public List<JournalEntry> releaseAll(@PathVariable String runId) {
        requireValid(runId);
        return dispatcher.releaseAll(runId);
    }

    @GetMapping("/journal")
    public List<JournalEntry> journal(@PathVariable String runId) {
        requireValid(runId);
        return dispatcher.journal(runId);
    }

    @DeleteMapping
    public ResetResponse reset(@PathVariable String runId) {
        requireValid(runId);
        ControlledDispatcher.ResetSummary summary = dispatcher.reset(runId);
        return new ResetResponse(runId, summary.discardedMessages(), summary.unusedInterventions());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    private static void requireValid(String runId) {
        if (!RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("runId must be 1-128 characters of letters, digits, '.', '_', ':' or '-'");
        }
    }

    private static Intervention toIntervention(InterventionRequest request) {
        if (request.type() == null) {
            throw new IllegalArgumentException("type is required: HOLD, DUPLICATE or DELAY");
        }
        return switch (request.type()) {
            case "HOLD" -> new Intervention.Hold();
            case "DUPLICATE" -> {
                if (request.copies() == null) {
                    throw new IllegalArgumentException("copies is required for DUPLICATE");
                }
                yield new Intervention.Duplicate(request.copies());
            }
            case "DELAY" -> {
                if (request.delayMs() == null) {
                    throw new IllegalArgumentException("delayMs is required for DELAY");
                }
                yield new Intervention.Delay(Duration.ofMillis(request.delayMs()));
            }
            default -> throw new IllegalArgumentException(
                    "unknown type '" + request.type() + "': expected HOLD, DUPLICATE or DELAY");
        };
    }
}
