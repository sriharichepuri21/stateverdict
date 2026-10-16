package dev.stateverdict.core.verdict;

import java.util.Collection;

/**
 * The developer-facing result, derived from execution status and rule evaluations by an explicit
 * precedence policy (spec section 5).
 *
 * <ol>
 *   <li><b>FAIL</b> if any rule is {@code VIOLATED}, regardless of execution status. A violation
 *       backed by evidence must never be erased by a later infrastructure problem.</li>
 *   <li><b>ERROR</b> if execution is {@code INFRA_ERROR}: no trustworthy business verdict exists.</li>
 *   <li><b>INCONCLUSIVE</b> if execution {@code TIMED_OUT}, any rule is {@code UNKNOWN}, or there
 *       are no rule results at all. Missing evidence never becomes a pass.</li>
 *   <li><b>PASS</b> only if execution {@code COMPLETED} and every rule is {@code SATISFIED}.</li>
 * </ol>
 */
public enum Verdict {
    PASS(0),
    FAIL(1),
    INCONCLUSIVE(2),
    ERROR(3);

    private final int exitCode;

    Verdict(int exitCode) {
        this.exitCode = exitCode;
    }

    /** Process exit code for CLI and CI use (spec section 8). */
    public int exitCode() {
        return exitCode;
    }

    public static Verdict derive(ExecutionStatus execution, Collection<EvaluationStatus> evaluations) {
        if (evaluations.contains(EvaluationStatus.VIOLATED)) {
            return FAIL;
        }
        if (execution == ExecutionStatus.INFRA_ERROR) {
            return ERROR;
        }
        if (execution == ExecutionStatus.TIMED_OUT
                || evaluations.isEmpty()
                || evaluations.contains(EvaluationStatus.UNKNOWN)) {
            return INCONCLUSIVE;
        }
        return PASS;
    }
}
