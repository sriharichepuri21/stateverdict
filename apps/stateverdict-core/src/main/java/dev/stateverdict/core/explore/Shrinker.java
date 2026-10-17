package dev.stateverdict.core.explore;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reduces a failing schedule to a <em>locally minimal</em> one that still fails.
 *
 * <h2>Algorithm</h2>
 * Greedy one-step delta debugging over {@link Reductions}: try each candidate reduction, keep the
 * first one that still fails, and start again from the smaller schedule, until no single reduction
 * still fails. The result is 1-minimal: removing or simplifying any one more thing makes it pass.
 * It is not guaranteed to be the globally smallest failing schedule.
 *
 * <h2>Why every candidate runs several times</h2>
 * In a distributed system one failing run proves little: the same schedule can fail once and pass the
 * next time. A candidate is accepted only if it violates a target rule in <em>all</em>
 * {@code acceptanceTrials} runs. The original and the result are then each measured over
 * {@code confirmTrials} runs, and both rates are reported, so a reduction that traded determinism for
 * size is visible rather than hidden.
 */
public final class Shrinker {

    private final ScheduleExecutor executor;
    private final int acceptanceTrials;
    private final int confirmTrials;
    private final Clock clock;
    private int runs;

    public Shrinker(ScheduleExecutor executor, int acceptanceTrials, int confirmTrials) {
        this(executor, acceptanceTrials, confirmTrials, Clock.systemUTC());
    }

    Shrinker(ScheduleExecutor executor, int acceptanceTrials, int confirmTrials, Clock clock) {
        if (acceptanceTrials < 1 || confirmTrials < 1) {
            throw new IllegalArgumentException("trial counts must be at least 1");
        }
        this.executor = executor;
        this.acceptanceTrials = acceptanceTrials;
        this.confirmTrials = confirmTrials;
        this.clock = clock;
    }

    /**
     * @param targetRules rules a run must violate to count as failing; if empty, the rules the
     *                    original violates during its confirmation runs are used
     */
    public ShrinkReport shrink(Schedule original, List<String> targetRules, PrintStream progress)
            throws InterruptedException {
        if (!original.isValid()) {
            throw new IllegalArgumentException("cannot shrink an invalid schedule: " + original.problems());
        }
        Instant started = clock.instant();
        runs = 0;

        List<List<String>> originalRuns = new ArrayList<>();
        for (int i = 0; i < confirmTrials; i++) {
            originalRuns.add(violated(executor.execute(original)));
            runs++;
        }
        Set<String> targets = new LinkedHashSet<>(targetRules);
        if (targets.isEmpty()) {
            originalRuns.forEach(targets::addAll);
        }
        int originalFailures = (int) originalRuns.stream().filter(violated -> violatesAny(violated, targets)).count();
        say(progress, "original: " + describe(original) + " -> fails " + originalFailures + "/" + confirmTrials
                + " (target " + (targets.isEmpty() ? "none" : String.join(", ", targets)) + ")");

        // Only a failure that reproduces every time can be shrunk honestly: otherwise a candidate that
        // "passes" may just have been unlucky, and the search would wander.
        if (targets.isEmpty() || originalFailures < confirmTrials) {
            say(progress, "not shrinking: the original failed " + originalFailures + "/" + confirmTrials
                    + ", so a smaller failing schedule could not be told apart from luck");
            return new ShrinkReport(ShrinkReport.Status.UNRELIABLE, List.copyOf(targets), original,
                    originalFailures, original, originalFailures, confirmTrials, acceptanceTrials, List.of(), 0, runs,
                    Duration.between(started, clock.instant()).toMillis());
        }

        Schedule current = original;
        List<String> accepted = new ArrayList<>();
        int tried = 0;
        boolean reduced = true;
        while (reduced) {
            reduced = false;
            for (Reductions.Candidate candidate : Reductions.of(current)) {
                tried++;
                int failures = failures(candidate.schedule(), targets, acceptanceTrials, true);
                boolean keep = failures == acceptanceTrials;
                say(progress, (keep ? "  keep   " : "  reject ") + candidate.change() + " -> fails " + failures + "/"
                        + acceptanceTrials);
                if (keep) {
                    current = candidate.schedule();
                    accepted.add(candidate.change());
                    reduced = true;
                    break;
                }
            }
        }

        int minimizedFailures = failures(current, targets, confirmTrials, false);
        say(progress, "minimized: " + describe(current) + " -> fails " + minimizedFailures + "/" + confirmTrials);
        return new ShrinkReport(ShrinkReport.Status.MINIMIZED, List.copyOf(targets), original, originalFailures,
                current.withSteps(original.name() + "-min", current.steps()), minimizedFailures, confirmTrials,
                acceptanceTrials, accepted, tried, runs, Duration.between(started, clock.instant()).toMillis());
    }

    /**
     * Runs {@code schedule} {@code trials} times and counts runs that violate a target rule.
     *
     * @param stopOnPass stop at the first passing run (enough to reject a candidate, saves time)
     */
    private int failures(Schedule schedule, Set<String> targets, int trials, boolean stopOnPass)
            throws InterruptedException {
        int failures = 0;
        for (int i = 0; i < trials; i++) {
            List<String> violated = violated(executor.execute(schedule));
            runs++;
            if (violatesAny(violated, targets)) {
                failures++;
            } else if (stopOnPass) {
                return failures;
            }
        }
        return failures;
    }

    private static List<String> violated(RunReport report) {
        return report.ruleResults().stream()
                .filter(result -> result.status() == EvaluationStatus.VIOLATED)
                .map(RuleResult::ruleId)
                .toList();
    }

    private static boolean violatesAny(List<String> violated, Set<String> targets) {
        return violated.stream().anyMatch(targets::contains);
    }

    private static String describe(Schedule schedule) {
        return Explorer.summary(schedule) + " [" + schedule.steps().size() + " steps, "
                + Reductions.interventions(schedule) + " interventions]";
    }

    private static void say(PrintStream progress, String line) {
        if (progress != null) {
            progress.println(line);
        }
    }

    /** Human-readable report. */
    public static String toConsole(ShrinkReport report) {
        StringBuilder out = new StringBuilder("\nShrink result: ").append(report.status()).append("\n");
        out.append("Target rules: ").append(String.join(", ", report.targetRules())).append("\n\n");
        out.append("Original (").append(Reductions.interventions(report.original())).append(" interventions, fails ")
                .append(report.originalFailures()).append('/').append(report.confirmTrials()).append("):\n");
        steps(out, report.original());
        if (report.status() == ShrinkReport.Status.MINIMIZED) {
            out.append("\nLocally minimal (").append(Reductions.interventions(report.minimized()))
                    .append(" interventions, fails ").append(report.minimizedFailures()).append('/')
                    .append(report.confirmTrials()).append("):\n");
            steps(out, report.minimized());
            out.append("\nRemoved:\n");
            if (report.accepted().isEmpty()) {
                out.append("  nothing: every single reduction made it pass\n");
            }
            report.accepted().forEach(change -> out.append("  - ").append(change).append("\n"));
        }
        out.append("\nCandidates tried: ").append(report.candidatesTried()).append(", runs: ").append(report.runs())
                .append(", ").append(report.durationMs() / 1000).append(" s\n");
        return out.toString();
    }

    private static void steps(StringBuilder out, Schedule schedule) {
        for (int i = 0; i < schedule.steps().size(); i++) {
            out.append("  ").append(i + 1).append(". ").append(schedule.steps().get(i).describe()).append("\n");
        }
    }
}
