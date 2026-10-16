package dev.stateverdict.core.verdict;

import static dev.stateverdict.core.verdict.EvaluationStatus.SATISFIED;
import static dev.stateverdict.core.verdict.EvaluationStatus.UNKNOWN;
import static dev.stateverdict.core.verdict.EvaluationStatus.VIOLATED;
import static dev.stateverdict.core.verdict.ExecutionStatus.COMPLETED;
import static dev.stateverdict.core.verdict.ExecutionStatus.INFRA_ERROR;
import static dev.stateverdict.core.verdict.ExecutionStatus.TIMED_OUT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VerdictTest {

    @Test
    void passRequiresCompletedExecutionAndEverySatisfied() {
        assertThat(Verdict.derive(COMPLETED, List.of(SATISFIED, SATISFIED))).isEqualTo(Verdict.PASS);
    }

    @Test
    void anyViolationIsAFailEvenIfExecutionDidNotComplete() {
        assertThat(Verdict.derive(COMPLETED, List.of(SATISFIED, VIOLATED))).isEqualTo(Verdict.FAIL);
        assertThat(Verdict.derive(TIMED_OUT, List.of(VIOLATED))).isEqualTo(Verdict.FAIL);
        assertThat(Verdict.derive(INFRA_ERROR, List.of(VIOLATED))).isEqualTo(Verdict.FAIL);
    }

    @Test
    void infrastructureErrorWithoutViolationIsAnError() {
        assertThat(Verdict.derive(INFRA_ERROR, List.of(SATISFIED))).isEqualTo(Verdict.ERROR);
        assertThat(Verdict.derive(INFRA_ERROR, List.of(UNKNOWN))).isEqualTo(Verdict.ERROR);
    }

    @Test
    void missingEvidenceOrTimeoutIsInconclusiveNeverPass() {
        assertThat(Verdict.derive(COMPLETED, List.of(SATISFIED, UNKNOWN))).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(Verdict.derive(TIMED_OUT, List.of(SATISFIED))).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(Verdict.derive(COMPLETED, List.of())).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void exitCodesMatchTheSpecification() {
        assertThat(Verdict.PASS.exitCode()).isZero();
        assertThat(Verdict.FAIL.exitCode()).isEqualTo(1);
        assertThat(Verdict.INCONCLUSIVE.exitCode()).isEqualTo(2);
        assertThat(Verdict.ERROR.exitCode()).isEqualTo(3);
    }
}
