package dev.stateverdict.core.explore;

import java.util.List;

/**
 * Result of shrinking one failing schedule.
 *
 * @param targetRules       the rules a run must violate to count as "still failing"
 * @param original          the schedule as found
 * @param originalFailures  how many of {@code confirmTrials} runs of the original violated a target rule
 * @param minimized         the smallest schedule found that still failed every acceptance trial
 *                          (the original itself if nothing could be removed, or if UNRELIABLE)
 * @param minimizedFailures how many of {@code confirmTrials} runs of the minimized schedule failed
 * @param acceptanceTrials  runs per candidate; a candidate is accepted only if all of them fail
 * @param accepted          the reductions that were kept, in order
 * @param candidatesTried   candidates run during the search
 * @param runs              total schedule runs, including confirmation
 */
public record ShrinkReport<S>(
        Status status,
        List<String> targetRules,
        S original,
        int originalFailures,
        S minimized,
        int minimizedFailures,
        int confirmTrials,
        int acceptanceTrials,
        List<String> accepted,
        int candidatesTried,
        int runs,
        long durationMs) {

    public enum Status {
        /** The original failed every confirmation run and was reduced as far as possible. */
        MINIMIZED,
        /** The original did not fail every confirmation run, so it was measured but not shrunk. */
        UNRELIABLE
    }

    public ShrinkReport {
        targetRules = List.copyOf(targetRules);
        accepted = List.copyOf(accepted);
    }
}
