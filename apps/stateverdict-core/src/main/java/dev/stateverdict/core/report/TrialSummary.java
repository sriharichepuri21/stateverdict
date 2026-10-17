package dev.stateverdict.core.report;

import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of running one scenario several times, each trial from clean state (spike step 5).
 *
 * <p>This is a measurement, not a verdict: it reports how often each outcome actually occurred.
 * {@link #overall()} is a single verdict only when every trial agreed; if they did not, the
 * scenario is not reproducible under this configuration and the overall result is
 * {@code INCONCLUSIVE}.
 */
public record TrialSummary(
        String scenario,
        String fault,
        int trials,
        Map<Verdict, Integer> verdictCounts,
        Map<ExecutionStatus, Integer> executionCounts,
        boolean consistent,
        Verdict overall,
        long minDurationMs,
        long medianDurationMs,
        long maxDurationMs,
        List<Trial> trialResults,
        List<RunReport> reports) {

    /** One row per trial. {@code observed} is the first rule's observed value, e.g. "2 captures". */
    public record Trial(int number, String runId, Verdict verdict, ExecutionStatus execution, String observed,
                        long durationMs) {
    }

    public TrialSummary {
        verdictCounts = Map.copyOf(verdictCounts);
        executionCounts = Map.copyOf(executionCounts);
        trialResults = List.copyOf(trialResults);
        reports = List.copyOf(reports);
    }

    /** @param fault a short description of the faults applied, e.g. "payment.requested delivered 2 times" */
    public static TrialSummary of(String scenario, String fault, List<RunReport> reports) {
        if (reports.isEmpty()) {
            throw new IllegalArgumentException("at least one trial is required");
        }
        Map<Verdict, Integer> verdicts = new EnumMap<>(Verdict.class);
        Map<ExecutionStatus, Integer> executions = new EnumMap<>(ExecutionStatus.class);
        List<Trial> rows = new java.util.ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            RunReport report = reports.get(i);
            verdicts.merge(report.verdict(), 1, Integer::sum);
            executions.merge(report.executionStatus(), 1, Integer::sum);
            String observed = report.ruleResults().stream().findFirst().map(RuleResult::observed).orElse("-");
            long duration = Duration.between(report.startedAt(), report.completedAt()).toMillis();
            rows.add(new Trial(i + 1, report.runId(), report.verdict(), report.executionStatus(), observed, duration));
        }
        boolean consistent = verdicts.size() == 1;
        Verdict overall = consistent ? verdicts.keySet().iterator().next() : Verdict.INCONCLUSIVE;
        long[] durations = rows.stream().mapToLong(Trial::durationMs).sorted().toArray();
        return new TrialSummary(scenario, fault, reports.size(), verdicts, executions, consistent, overall,
                durations[0], durations[(durations.length - 1) / 2], durations[durations.length - 1], rows, reports);
    }

    /** How many trials ended with {@code verdict}. */
    public int count(Verdict verdict) {
        return verdictCounts.getOrDefault(verdict, 0);
    }

    public String toConsole() {
        StringBuilder out = new StringBuilder();
        out.append("StateVerdict v").append(RunReport.VERSION).append(": repeated trials\n\n");
        out.append("Scenario: ").append(scenario).append("\n");
        out.append("Fault:    ").append(fault).append("\n");
        out.append("Trials:   ").append(trials).append(", each from clean state (new run id, new order, run reset)\n\n");
        out.append(String.format("  %-5s %-14s %-13s %-12s %-14s %s%n", "#", "run", "verdict", "execution", "observed", "ms"));
        for (Trial trial : trialResults) {
            out.append(String.format("  %-5d %-14s %-13s %-12s %-14s %d%n", trial.number(), trial.runId(),
                    trial.verdict(), trial.execution(), trial.observed(), trial.durationMs()));
        }
        out.append("\nVerdicts: ");
        for (Verdict verdict : Verdict.values()) {
            out.append(verdict).append(' ').append(count(verdict)).append('/').append(trials)
                    .append(verdict == Verdict.ERROR ? "" : ", ");
        }
        out.append("\nDuration: min ").append(minDurationMs).append(" ms, median ").append(medianDurationMs)
                .append(" ms, max ").append(maxDurationMs).append(" ms\n");
        out.append("Consistent: ").append(consistent ? "yes" : "NO: trials disagreed").append("\n");
        out.append("\nOverall: ").append(overall).append("\n");
        return out.toString();
    }
}
