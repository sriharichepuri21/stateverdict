package dev.stateverdict.core.saga;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * SAGA-1: every order is decided: it ends CONFIRMED, REJECTED or ROLLBACK. An order that stays NEW
 * leaves whatever was reserved for it reserved. Eventual: re-observed within the runner's window.
 */
public final class OrderDecidedRule implements Rule {

    public static final String ID = "SAGA-1";

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
        return "Every order is decided (CONFIRMED, REJECTED or ROLLBACK)";
    }

    @Override
    public boolean eventual() {
        return true;
    }

    @Override
    public RuleResult evaluate(EvidenceBundle evidence) {
        Optional<EvidenceRecord> order = evidence.find(SagaEvidence.ORDER);
        if (order.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "not observed", "The order service's state could not be read.");
        }
        JsonNode found = order.get().body().path("found");
        if (found.isBoolean() && !found.booleanValue()) {
            return result(EvaluationStatus.VIOLATED, "order absent",
                    "The order was accepted but never appeared in the order service's state.");
        }
        String status = order.get().body().path("status").asString("");
        if (SagaEvidence.decided(status)) {
            return result(EvaluationStatus.SATISFIED, status, "The order was decided: " + status + ".");
        }
        return result(EvaluationStatus.VIOLATED, status.isEmpty() ? "no status" : status,
                "The order was never decided; it is still " + (status.isEmpty() ? "without a status" : status)
                        + ". Anything reserved for it stays reserved.");
    }

    private RuleResult result(EvaluationStatus status, String observed, String rationale) {
        return new RuleResult(ID, version(), description(), status, "CONFIRMED, REJECTED or ROLLBACK", observed,
                rationale, List.of(SagaEvidence.ORDER));
    }
}
