package dev.stateverdict.core.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrialSummaryTest {

    private static RunReport report(String runId, Verdict verdict, ExecutionStatus execution, long durationMs) {
        RuleResult rule = new RuleResult("SV-002", 1, "d",
                verdict == Verdict.FAIL ? EvaluationStatus.VIOLATED : EvaluationStatus.SATISFIED,
                "at most 1 capture", verdict == Verdict.FAIL ? "2 captures" : "1 capture", "r", List.of());
        Instant start = Instant.parse("2026-10-16T00:00:00Z");
        return new RunReport(RunReport.VERSION, runId, "s", 1, List.of(), execution, List.of(rule), verdict,
                List.of(), List.of(), start, start.plusMillis(durationMs));
    }

    @Test
    void allTrialsAgreeingGivesThatVerdict() {
        TrialSummary summary = TrialSummary.of("s", 2, List.of(
                report("a", Verdict.FAIL, ExecutionStatus.COMPLETED, 100),
                report("b", Verdict.FAIL, ExecutionStatus.COMPLETED, 300),
                report("c", Verdict.FAIL, ExecutionStatus.COMPLETED, 200)));

        assertThat(summary.consistent()).isTrue();
        assertThat(summary.overall()).isEqualTo(Verdict.FAIL);
        assertThat(summary.count(Verdict.FAIL)).isEqualTo(3);
        assertThat(summary.count(Verdict.PASS)).isZero();
        assertThat(summary.executionCounts()).containsEntry(ExecutionStatus.COMPLETED, 3);
    }

    @Test
    void disagreeingTrialsAreInconclusiveAndEveryOutcomeIsCounted() {
        TrialSummary summary = TrialSummary.of("s", 2, List.of(
                report("a", Verdict.FAIL, ExecutionStatus.COMPLETED, 100),
                report("b", Verdict.PASS, ExecutionStatus.COMPLETED, 100),
                report("c", Verdict.INCONCLUSIVE, ExecutionStatus.TIMED_OUT, 100)));

        assertThat(summary.consistent()).isFalse();
        assertThat(summary.overall()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(summary.count(Verdict.FAIL)).isEqualTo(1);
        assertThat(summary.count(Verdict.PASS)).isEqualTo(1);
        assertThat(summary.count(Verdict.INCONCLUSIVE)).isEqualTo(1);
        assertThat(summary.executionCounts()).containsEntry(ExecutionStatus.TIMED_OUT, 1);
    }

    @Test
    void durationsAreSummarised() {
        TrialSummary summary = TrialSummary.of("s", 2, List.of(
                report("a", Verdict.PASS, ExecutionStatus.COMPLETED, 300),
                report("b", Verdict.PASS, ExecutionStatus.COMPLETED, 100),
                report("c", Verdict.PASS, ExecutionStatus.COMPLETED, 200)));

        assertThat(summary.minDurationMs()).isEqualTo(100);
        assertThat(summary.medianDurationMs()).isEqualTo(200);
        assertThat(summary.maxDurationMs()).isEqualTo(300);
    }

    @Test
    void trialRowsKeepOrderAndObservedValues() {
        TrialSummary summary = TrialSummary.of("s", 2, List.of(
                report("a", Verdict.FAIL, ExecutionStatus.COMPLETED, 100),
                report("b", Verdict.PASS, ExecutionStatus.COMPLETED, 100)));

        assertThat(summary.trialResults()).extracting(TrialSummary.Trial::runId).containsExactly("a", "b");
        assertThat(summary.trialResults()).extracting(TrialSummary.Trial::observed)
                .containsExactly("2 captures", "1 capture");
        assertThat(summary.toConsole()).contains("FAIL 1/2", "PASS 1/2", "NO: trials disagreed", "Overall: INCONCLUSIVE");
    }

    @Test
    void zeroTrialsIsRejected() {
        assertThatThrownBy(() -> TrialSummary.of("s", 2, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
