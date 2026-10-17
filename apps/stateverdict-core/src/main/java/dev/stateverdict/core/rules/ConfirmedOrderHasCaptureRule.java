package dev.stateverdict.core.rules;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * SV-001: a CONFIRMED order has a committed payment capture.
 *
 * <p>The specification's first invariant: an order must never ship without the money having been
 * taken. Evidence: {@code order.state} (order service) and {@code payment.captures} (payment
 * service), both committed state read at the end of the run. Two services, two databases: this is a
 * cross-service rule that neither service can check on its own.
 *
 * <p>An order that is not CONFIRMED satisfies the rule trivially. "At most one capture" is SV-002.
 */
public final class ConfirmedOrderHasCaptureRule implements Rule {

    public static final String ID = "SV-001";

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
        return "A CONFIRMED order has a committed payment capture";
    }

    @Override
    public RuleResult evaluate(EvidenceBundle evidence) {
        Optional<EvidenceRecord> order = evidence.find(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE);
        if (order.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "not observed", "No committed order state was collected.", List.of());
        }
        String status = order.get().body().path("status").asString("");
        String orderId = order.get().body().path("id").asString("unknown");
        if (status.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "unreadable", "The order evidence has no status.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE));
        }
        if (!"CONFIRMED".equals(status)) {
            return result(EvaluationStatus.SATISFIED, status, "Order " + orderId + " is " + status + ", not CONFIRMED.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE));
        }
        Optional<EvidenceRecord> captures = evidence.find(CaptureUniquenessRule.CAPTURES_EVIDENCE);
        if (captures.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "CONFIRMED, captures not observed",
                    "Order " + orderId + " is CONFIRMED but no capture evidence was collected.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE));
        }
        JsonNode list = captures.get().body().path("captures");
        if (!list.isArray()) {
            return result(EvaluationStatus.UNKNOWN, "CONFIRMED, captures unreadable", "The capture evidence has no list.",
                    List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE, CaptureUniquenessRule.CAPTURES_EVIDENCE));
        }
        List<String> refs = List.of(CancelledOrderNotConfirmedRule.ORDER_EVIDENCE, CaptureUniquenessRule.CAPTURES_EVIDENCE);
        if (list.isEmpty()) {
            return result(EvaluationStatus.VIOLATED, "CONFIRMED, 0 captures",
                    "Order " + orderId + " is CONFIRMED, but the payment service has no committed capture for it.", refs);
        }
        return result(EvaluationStatus.SATISFIED, "CONFIRMED, " + list.size() + (list.size() == 1 ? " capture" : " captures"),
                "Order " + orderId + " is CONFIRMED and paid.", refs);
    }

    private RuleResult result(EvaluationStatus status, String observed, String rationale, List<String> refs) {
        return new RuleResult(ID, version(), description(), status, "CONFIRMED implies at least 1 capture", observed,
                rationale, refs);
    }
}
