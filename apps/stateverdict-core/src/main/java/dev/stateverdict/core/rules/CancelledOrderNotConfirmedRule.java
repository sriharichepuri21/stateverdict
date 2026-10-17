package dev.stateverdict.core.rules;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * SV-003: an order the customer cancelled never ends CONFIRMED.
 *
 * <p>Evidence: {@code order.state}, the order service's committed state ({@code GET /orders/{id}})
 * read at the end of the run. {@code cancelRequestedAt} is set once when a cancellation is accepted
 * and never cleared, so it survives a buggy handler overwriting the status.
 *
 * <p>An order that was never cancelled satisfies the rule trivially. Whether the money for a
 * cancelled order was refunded is a different rule.
 */
public final class CancelledOrderNotConfirmedRule implements Rule {

    public static final String ID = "SV-003";
    public static final String ORDER_EVIDENCE = "order.state";

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
        return "An order the customer cancelled never ends CONFIRMED";
    }

    @Override
    public RuleResult evaluate(EvidenceBundle evidence) {
        Optional<EvidenceRecord> order = evidence.find(ORDER_EVIDENCE);
        if (order.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "not observed",
                    "No committed order state was collected.", List.of());
        }
        JsonNode body = order.get().body();
        String status = body.path("status").asString("");
        if (status.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "unreadable",
                    "The order evidence has no status.", List.of(ORDER_EVIDENCE));
        }
        JsonNode cancelRequestedAt = body.path("cancelRequestedAt");
        boolean cancelled = !cancelRequestedAt.isMissingNode() && !cancelRequestedAt.isNull();
        String orderId = body.path("id").asString("unknown");
        if (!cancelled) {
            return result(EvaluationStatus.SATISFIED, status + ", not cancelled",
                    "Order " + orderId + " was never cancelled by the customer.", List.of(ORDER_EVIDENCE));
        }
        String observed = status + ", cancelled at " + cancelRequestedAt.asString("?");
        if ("CONFIRMED".equals(status)) {
            return result(EvaluationStatus.VIOLATED, observed,
                    "The customer cancelled order " + orderId + " at " + cancelRequestedAt.asString("?")
                            + ", but its final status is CONFIRMED.", List.of(ORDER_EVIDENCE));
        }
        return result(EvaluationStatus.SATISFIED, observed,
                "Order " + orderId + " was cancelled and stayed " + status + ".", List.of(ORDER_EVIDENCE));
    }

    private RuleResult result(EvaluationStatus status, String observed, String rationale, List<String> refs) {
        return new RuleResult(ID, version(), description(), status, "cancelled order is not CONFIRMED", observed,
                rationale, refs);
    }
}
