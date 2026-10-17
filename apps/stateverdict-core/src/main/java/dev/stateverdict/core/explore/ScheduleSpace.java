package dev.stateverdict.core.explore;

import java.util.List;
import java.util.Map;

/**
 * Everything the explorer and the shrinker need to know about one kind of schedule. This is what
 * keeps the engine independent of the system under test: the commerce example and the external saga
 * target each provide a space, and the same {@link Explorer} and {@link Shrinker} run on both.
 *
 * @param <S> the schedule type
 */
public interface ScheduleSpace<S> {

    /** A seeded source of valid schedules: the same seed yields the same sequence. */
    @FunctionalInterface
    interface Generator<S> {
        S next();
    }

    /** A smaller or simpler schedule, and a description of what was taken away. */
    record Candidate<S>(S schedule, String change) {
    }

    Generator<S> generator(long seed);

    /** Problems with a schedule, empty if valid. */
    List<String> problems(S schedule);

    String name(S schedule);

    S renamed(S schedule, String name);

    /** Valid reductions of {@code schedule}, biggest first. */
    List<Candidate<S>> reductions(S schedule);

    /** A rough size used to report how much a shrink removed. */
    int interventions(S schedule);

    /** One line describing the schedule's faults and conditions. */
    String summary(S schedule);

    /** The schedule as numbered, human-readable steps. */
    List<String> steps(S schedule);

    /** Counts what this schedule exercises, so a campaign can report what it did and did not explore. */
    void recordCoverage(S schedule, Map<String, Integer> coverage);
}
