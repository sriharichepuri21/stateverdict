package dev.stateverdict.core.rules;

import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.List;

/**
 * The outcome of evaluating one rule, with enough detail for a person to check the conclusion.
 *
 * @param evidenceRefs ids of the {@code EvidenceRecord}s the conclusion is based on
 */
public record RuleResult(
        String ruleId,
        int ruleVersion,
        String description,
        EvaluationStatus status,
        String expected,
        String observed,
        String rationale,
        List<String> evidenceRefs) {

    public RuleResult {
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
