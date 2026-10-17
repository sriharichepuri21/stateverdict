package dev.stateverdict.core.explore;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs generated schedules one after another and records which ones violate a rule.
 *
 * <p>Each finding is saved as an ordinary schedule file, so it can be rerun, measured with
 * {@code --trials} and shrunk. A finding from a single run is a lead, not a verdict on
 * reproducibility: some failures depend on timing and must be confirmed by repeated trials.
 *
 * <p>Independent of the system under test: what a schedule is, how it is generated and described
 * comes from its {@link ScheduleSpace}.
 */
public final class Explorer<S> {

    private final ScheduleSpace<S> space;
    private final ScheduleExecutor<S> executor;
    private final JsonMapper json;
    private final Clock clock;

    public Explorer(ScheduleSpace<S> space, ScheduleExecutor<S> executor, JsonMapper json) {
        this(space, executor, json, Clock.systemUTC());
    }

    Explorer(ScheduleSpace<S> space, ScheduleExecutor<S> executor, JsonMapper json, Clock clock) {
        this.space = space;
        this.executor = executor;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param saveDir     where to write failing schedules; {@code null} to not save them
     * @param stopOnFirst stop at the first finding instead of completing the campaign
     */
    public ExplorationReport<S> explore(long seed, int iterations, Path saveDir, boolean stopOnFirst,
                                        PrintStream progress) throws InterruptedException, IOException {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1");
        }
        Instant started = clock.instant();
        ScheduleSpace.Generator<S> generator = space.generator(seed);
        Map<Verdict, Integer> verdicts = new EnumMap<>(Verdict.class);
        Map<String, Integer> coverage = new TreeMap<>();
        List<ExplorationReport.Finding<S>> findings = new ArrayList<>();

        int ran = 0;
        for (int i = 1; i <= iterations; i++) {
            S schedule = generator.next();
            List<String> problems = space.problems(schedule);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("generator produced an invalid schedule: " + problems);
            }
            space.recordCoverage(schedule, coverage);
            RunReport report = executor.execute(schedule);
            ran++;
            verdicts.merge(report.verdict(), 1, Integer::sum);
            List<String> violated = report.ruleResults().stream()
                    .filter(result -> result.status() == EvaluationStatus.VIOLATED)
                    .map(RuleResult::ruleId)
                    .toList();
            String line = String.format("[%3d/%d] %-12s %s", i, iterations, report.verdict(), space.summary(schedule));
            if (!violated.isEmpty()) {
                String savedAs = save(saveDir, schedule, violated);
                findings.add(new ExplorationReport.Finding<>(i, schedule, report.runId(), violated, savedAs, report));
                line += "   <-- violates " + String.join(", ", violated) + (savedAs == null ? "" : ", saved " + savedAs);
            }
            if (progress != null) {
                progress.println(line);
            }
            if (stopOnFirst && !findings.isEmpty()) {
                break;
            }
        }

        Verdict overall = overall(verdicts);
        return new ExplorationReport<>(seed, ran, verdicts, coverage, findings, overall,
                Duration.between(started, clock.instant()).toMillis());
    }

    /** One line describing a commerce schedule's faults and customer action. */
    public static String summary(Schedule schedule) {
        return new CommerceSpace().summary(schedule);
    }

    private String save(Path saveDir, S schedule, List<String> violated) throws IOException {
        if (saveDir == null) {
            return null;
        }
        Files.createDirectories(saveDir);
        String name = String.join("-", violated).toLowerCase() + "-" + space.name(schedule) + ".json";
        Path file = saveDir.resolve(name);
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(schedule));
        return file.toString();
    }

    private static Verdict overall(Map<Verdict, Integer> verdicts) {
        for (Verdict verdict : List.of(Verdict.FAIL, Verdict.ERROR, Verdict.INCONCLUSIVE)) {
            if (verdicts.getOrDefault(verdict, 0) > 0) {
                return verdict;
            }
        }
        return Verdict.PASS;
    }

    /** Human-readable summary of a finished campaign. */
    public String toConsole(ExplorationReport<S> report) {
        StringBuilder out = new StringBuilder();
        out.append("\nExploration: seed ").append(report.seed()).append(", ").append(report.iterations())
                .append(" generated schedules, ").append(report.durationMs() / 1000).append(" s\n\n");
        out.append("Verdicts:  ");
        for (Verdict verdict : Verdict.values()) {
            out.append(verdict).append(' ').append(report.count(verdict)).append("  ");
        }
        out.append("\n\nCoverage (how often each option was generated):\n");
        report.coverage().forEach((key, count) -> out.append(String.format("  %-36s %d%n", key, count)));
        out.append("\nFindings: ").append(report.findings().size()).append("\n");
        for (ExplorationReport.Finding<S> finding : report.findings()) {
            out.append("  #").append(finding.iteration()).append("  ").append(String.join(", ", finding.violatedRules()))
                    .append("  ").append(space.summary(finding.schedule())).append("\n");
            List<String> steps = space.steps(finding.schedule());
            for (int i = 0; i < steps.size(); i++) {
                out.append("        ").append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
            if (finding.savedAs() != null) {
                out.append("        saved: ").append(finding.savedAs()).append("\n");
            }
        }
        out.append("\nOverall: ").append(report.overall()).append("\n");
        return out.toString();
    }

    /** Distinct rule ids violated anywhere in the campaign. */
    public static List<String> violatedRules(ExplorationReport<?> report) {
        return report.findings().stream().flatMap(f -> f.violatedRules().stream()).distinct().sorted().toList();
    }
}
