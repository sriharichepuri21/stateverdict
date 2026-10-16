package dev.stateverdict.core.rules;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * SV-002: at most one committed payment capture per business order.
 *
 * <p>Evidence: {@code payment.captures}, the payment service's committed-state response for the
 * order ({@code GET /captures?orderId=}). It counts committed captures, not deliveries: a duplicate
 * delivery handled idempotently satisfies this rule.
 */
public final class CaptureUniquenessRule implements Rule {

    public static final String ID = "SV-002";
    public static final String CAPTURES_EVIDENCE = "payment.captures";

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
        return "At most one committed payment capture per order";
    }

    @Override
    public RuleResult evaluate(EvidenceBundle evidence) {
        Optional<EvidenceRecord> captures = evidence.find(CAPTURES_EVIDENCE);
        if (captures.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "not observed",
                    "No committed-capture evidence was collected, so uniqueness cannot be established.", List.of());
        }
        JsonNode body = captures.get().body();
        JsonNode list = body.path("captures");
        if (!list.isArray()) {
            return result(EvaluationStatus.UNKNOWN, "unreadable",
                    "The capture evidence has no 'captures' array.", List.of(CAPTURES_EVIDENCE));
        }
        int count = list.size();
        String orderId = body.path("orderId").asString("unknown");
        String observed = count + (count == 1 ? " capture" : " captures");
        if (count <= 1) {
            return result(EvaluationStatus.SATISFIED, observed,
                    "Order " + orderId + " has " + observed + ".", List.of(CAPTURES_EVIDENCE));
        }
        return result(EvaluationStatus.VIOLATED, observed,
                "Order " + orderId + " was captured " + count + " times; captured event ids: "
                        + eventIds(list) + ".", List.of(CAPTURES_EVIDENCE));
    }

    private static String eventIds(JsonNode captures) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < captures.size(); i++) {
            if (i > 0) {
                ids.append(", ");
            }
            ids.append(captures.get(i).path("eventId").asString("?"));
        }
        return ids.toString();
    }

    private RuleResult result(EvaluationStatus status, String observed, String rationale, List<String> refs) {
        return new RuleResult(ID, version(), description(), status, "at most 1 capture", observed, rationale, refs);
    }
}
