package dev.stateverdict.core.report;

import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable result of one run (spec appendix A). Contains the full evidence, so the verdict
 * can be re-derived later with {@code stateverdict replay-evidence}.
 *
 * @param faultSchedule the interventions applied, in order
 * @param notes         anything that affected trust in the result (failed reads, cleanup errors)
 */
public record RunReport(
        String stateverdictVersion,
        String runId,
        String scenario,
        int scenarioVersion,
        List<Map<String, Object>> faultSchedule,
        ExecutionStatus executionStatus,
        List<RuleResult> ruleResults,
        Verdict verdict,
        List<EvidenceRecord> evidence,
        List<String> notes,
        Instant startedAt,
        Instant completedAt) {

    public static final String VERSION = "0.1.0";

    public RunReport {
        faultSchedule = List.copyOf(faultSchedule);
        ruleResults = List.copyOf(ruleResults);
        evidence = List.copyOf(evidence);
        notes = List.copyOf(notes);
    }
}
