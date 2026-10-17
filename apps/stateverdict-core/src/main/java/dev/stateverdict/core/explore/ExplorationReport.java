package dev.stateverdict.core.explore;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.verdict.Verdict;
import java.util.List;
import java.util.Map;

/**
 * Result of an exploration campaign.
 *
 * @param verdictCounts how many generated schedules ended with each verdict
 * @param coverage      how often each kind of fault and customer action was generated, so the reader
 *                      can see what was and was not explored
 * @param findings      every schedule whose run violated a rule, in the order found
 * @param overall       FAIL if anything violated a rule; otherwise ERROR, INCONCLUSIVE or PASS by the
 *                      usual precedence
 */
public record ExplorationReport<S>(
        long seed,
        int iterations,
        Map<Verdict, Integer> verdictCounts,
        Map<String, Integer> coverage,
        List<Finding<S>> findings,
        Verdict overall,
        long durationMs) {

    /**
     * One rule violation found by a generated schedule.
     *
     * @param iteration     1-based position in the campaign
     * @param violatedRules ids of the rules this run violated, e.g. {@code [SV-003]}
     * @param savedAs       where the schedule was written, so it can be rerun with run-schedule
     */
    public record Finding<S>(int iteration, S schedule, String runId, List<String> violatedRules, String savedAs,
                          RunReport report) {
    }

    public ExplorationReport {
        verdictCounts = Map.copyOf(verdictCounts);
        coverage = Map.copyOf(coverage);
        findings = List.copyOf(findings);
    }

    public int count(Verdict verdict) {
        return verdictCounts.getOrDefault(verdict, 0);
    }
}
