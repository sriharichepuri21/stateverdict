package dev.stateverdict.core.rules;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * SV-004: if the customer cancelled an order whose payment was captured, every capture is refunded,
 * within the observation window.
 *
 * <p>A bounded eventual rule ({@link #eventual()}): the refund is a consequence that travels through
 * two more messages after the capture, so the runner keeps re-observing for a bounded window (default
 * 5 s after the workflow completes) while this rule is not yet satisfied. The bound is measured on
 * StateVerdict's own observation clock, not on event timestamps.
 *
 * <p>Evidence: {@code order.state} (was it cancelled?), {@code payment.captures} and
 * {@code payment.refunds} (payment service, {@code GET /refunds}).
 */
public final class RefundForCancelledCaptureRule implements Rule {

    public static final String ID = "SV-004";
    public static final String REFUNDS_EVIDENCE = "payment.refunds";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "A captured payment for an order the customer cancelled is refunded";
    }

    @Override
    public boolean eventual() {
        return true;
    }

    @Override
    public RuleResult evaluate(EvidenceBundle evidence) {
        Optional<EvidenceRecord> order = evidence.find(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE);
        if (order.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "not observed", "No committed order state was collected.", List.of());
        }
        JsonNode cancelRequestedAt = order.get().body().path("cancelRequestedAt");
        String orderId = order.get().body().path("id").asString("unknown");
        if (cancelRequestedAt.isMissingNode() || cancelRequestedAt.isNull()) {
            return result(EvaluationStatus.SATISFIED, "not cancelled", "Order " + orderId + " was never cancelled.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE));
        }
        Optional<EvidenceRecord> captures = evidence.find(CaptureUniquenessRule.CAPTURES_EVIDENCE);
        if (captures.isEmpty() || !captures.get().body().path("captures").isArray()) {
            return result(EvaluationStatus.UNKNOWN, "cancelled, captures not observed",
                    "Order " + orderId + " was cancelled but its captures could not be read.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE));
        }
        JsonNode captureList = captures.get().body().path("captures");
        if (captureList.isEmpty()) {
            return result(EvaluationStatus.SATISFIED, "cancelled, nothing captured",
                    "Order " + orderId + " was cancelled and no money was taken.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE, CaptureUniquenessRule.CAPTURES_EVIDENCE));
        }
        List<String> refs = List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE, CaptureUniquenessRule.CAPTURES_EVIDENCE,
                REFUNDS_EVIDENCE);
        Optional<EvidenceRecord> refunds = evidence.find(REFUNDS_EVIDENCE);
        if (refunds.isEmpty() || !refunds.get().body().path("refunds").isArray()) {
            return result(EvaluationStatus.UNKNOWN, "cancelled, " + captureList.size() + " captured, refunds not observed",
                    "Order " + orderId + " was cancelled after capture, but refunds could not be read.", refs);
        }
        Set<String> refunded = new HashSet<>();
        JsonNode refundList = refunds.get().body().path("refunds");
        for (int i = 0; i < refundList.size(); i++) {
            refunded.add(refundList.get(i).path("captureId").asString(""));
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < captureList.size(); i++) {
            String captureId = captureList.get(i).path("id").asString("?");
            if (!refunded.contains(captureId)) {
                missing.add(captureId);
            }
        }
        String observed = "cancelled, " + captureList.size() + " captured, " + (captureList.size() - missing.size())
                + " refunded";
        if (missing.isEmpty()) {
            return result(EvaluationStatus.SATISFIED, observed, "Every capture of cancelled order " + orderId
                    + " was refunded.", refs);
        }
        return result(EvaluationStatus.VIOLATED, observed, "The customer cancelled order " + orderId
                + ", but captures " + String.join(", ", missing) + " were never refunded.", refs);
    }

    private RuleResult result(EvaluationStatus status, String observed, String rationale, List<String> refs) {
        return new RuleResult(ID, version(), description(), status, "every capture of a cancelled order refunded",
                observed, rationale, refs);
    }
}
