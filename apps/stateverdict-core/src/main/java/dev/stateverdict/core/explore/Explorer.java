package dev.stateverdict.core.explore;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Step;
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
 * {@code --trials} and, next, shrunk. A finding from a single run is a lead, not a verdict on
 * reproducibility: some failures depend on timing and must be confirmed by repeated trials.
 */
public final class Explorer {

    private final ScheduleExecutor executor;
    private final JsonMapper json;
    private final Clock clock;

    public Explorer(ScheduleExecutor executor, JsonMapper json) {
        this(executor, json, Clock.systemUTC());
    }

    Explorer(ScheduleExecutor executor, JsonMapper json, Clock clock) {
        this.executor = executor;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param saveDir     where to write failing schedules; {@code null} to not save them
     * @param stopOnFirst stop at the first finding instead of completing the campaign
     */
    public ExplorationReport explore(long seed, int iterations, Path saveDir, boolean stopOnFirst, PrintStream progress)
            throws InterruptedException, IOException {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1");
        }
        Instant started = clock.instant();
        ScheduleGenerator generator = new ScheduleGenerator(seed);
        Map<Verdict, Integer> verdicts = new EnumMap<>(Verdict.class);
        Map<String, Integer> coverage = new TreeMap<>();
        List<ExplorationReport.Finding> findings = new ArrayList<>();

        int ran = 0;
        for (int i = 1; i <= iterations; i++) {
            Schedule schedule = generator.next();
            if (!schedule.isValid()) {
                throw new IllegalStateException("generator produced an invalid schedule: " + schedule.problems());
            }
            recordCoverage(schedule, coverage);
            RunReport report = executor.execute(schedule);
            ran++;
            verdicts.merge(report.verdict(), 1, Integer::sum);
            List<String> violated = report.ruleResults().stream()
                    .filter(result -> result.status() == EvaluationStatus.VIOLATED)
                    .map(RuleResult::ruleId)
                    .toList();
            String line = String.format("[%3d/%d] %-12s %s", i, iterations, report.verdict(), summary(schedule));
            if (!violated.isEmpty()) {
                String savedAs = save(saveDir, schedule, violated);
                findings.add(new ExplorationReport.Finding(i, schedule, report.runId(), violated, savedAs, report));
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
        return new ExplorationReport(seed, ran, verdicts, coverage, findings, overall,
                Duration.between(started, clock.instant()).toMillis());
    }

    /** One line describing a schedule's faults and customer action. */
    public static String summary(Schedule schedule) {
        List<String> parts = new ArrayList<>();
        for (Step.Arm arm : schedule.arms()) {
            parts.add(arm.topic() + ":" + arm.fault());
        }
        if (parts.isEmpty()) {
            parts.add("no faults");
        }
        parts.add(cancelMode(schedule));
        return String.join(", ", parts);
    }

    private static String cancelMode(Schedule schedule) {
        List<Step> steps = schedule.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.Cancel) {
                return i > 0 && steps.get(i - 1) instanceof Step.AwaitHeld await
                        ? "cancel while " + await.topic() + " held"
                        : "cancel right after order";
            }
        }
        return "no cancel";
    }

    private static void recordCoverage(Schedule schedule, Map<String, Integer> coverage) {
        for (Step.Arm arm : schedule.arms()) {
            coverage.merge(arm.topic() + " " + arm.fault().type(), 1, Integer::sum);
        }
        for (String topic : List.of("payment.requested", "payment.completed")) {
            boolean armed = schedule.arms().stream().anyMatch(arm -> arm.topic().equals(topic));
            if (!armed) {
                coverage.merge(topic + " none", 1, Integer::sum);
            }
        }
        coverage.merge("customer: " + cancelMode(schedule), 1, Integer::sum);
        boolean declined = schedule.steps().stream().anyMatch(step ->
                step instanceof Step.PlaceOrder place && place.amountCents() == ScheduleGenerator.DECLINED_AMOUNT);
        coverage.merge(declined ? "amount: declined" : "amount: approved", 1, Integer::sum);
    }

    private String save(Path saveDir, Schedule schedule, List<String> violated) throws IOException {
        if (saveDir == null) {
            return null;
        }
        Files.createDirectories(saveDir);
        String name = String.join("-", violated).toLowerCase() + "-" + schedule.name() + ".json";
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
    public static String toConsole(ExplorationReport report) {
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
        for (ExplorationReport.Finding finding : report.findings()) {
            out.append("  #").append(finding.iteration()).append("  ").append(String.join(", ", finding.violatedRules()))
                    .append("  ").append(summary(finding.schedule())).append("\n");
            for (int i = 0; i < finding.schedule().steps().size(); i++) {
                out.append("        ").append(i + 1).append(". ").append(finding.schedule().steps().get(i).describe())
                        .append("\n");
            }
            if (finding.savedAs() != null) {
                out.append("        saved: ").append(finding.savedAs()).append("\n");
            }
        }
        out.append("\nOverall: ").append(report.overall()).append("\n");
        return out.toString();
    }

    /** Distinct rule ids violated anywhere in the campaign. */
    public static List<String> violatedRules(ExplorationReport report) {
        return report.findings().stream().flatMap(f -> f.violatedRules().stream()).distinct().sorted().toList();
    }
}
