package dev.stateverdict.core.cli;

import dev.stateverdict.core.report.ReportWriter;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.report.TrialSummary;
import dev.stateverdict.core.rules.CancelledOrderNotConfirmedRule;
import dev.stateverdict.core.rules.CaptureUniquenessRule;
import dev.stateverdict.core.rules.ConfirmedOrderHasCaptureRule;
import dev.stateverdict.core.rules.RefundForCancelledCaptureRule;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.runner.DuplicateDeliveryScenario;
import dev.stateverdict.core.runner.ScheduleRunner;
import dev.stateverdict.core.saga.SagaRunner;
import dev.stateverdict.core.saga.SagaSchedule;
import dev.stateverdict.core.schedule.Schedule;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Command-line entry point.
 *
 * <pre>
 * stateverdict run checkout-duplicate-payment-request [--order-url URL] [--payment-url URL]
 *                  [--copies N] [--deadline-ms MS] [--settle-ms MS] [--trials N] [--report PATH]
 * stateverdict run-schedule SCHEDULE.json [--order-url URL] [--payment-url URL] [--deadline-ms MS]
 *                  [--settle-ms MS] [--trials N] [--report PATH]
 * stateverdict explore [--iterations N] [--seed S] [--out DIR] [--stop-on-first true] [--report PATH] ...
 * stateverdict shrink SCHEDULE.json [--trials K] [--confirm N] [--rules R1,R2] [--out PATH] [--report PATH] ...
 * stateverdict run-saga SCHEDULE.json [--order-url URL] [--payment-url URL] [--stock-url URL]
 *                  [--deadline-ms MS] [--trials N] [--report PATH]
 * stateverdict replay-evidence REPORT.json
 * </pre>
 *
 * Exit codes: 0 PASS, 1 FAIL, 2 INCONCLUSIVE, 3 ERROR or invalid usage (spec section 8). With
 * {@code --trials N > 1}, the exit code is the shared verdict if every trial agreed, otherwise 2:
 * a result that does not reproduce is not a verified result.
 */
public final class Main {

    static final int USAGE_ERROR = 3;

    private static final String USAGE = """
            Usage:
              stateverdict run checkout-duplicate-payment-request [options]
                --order-url URL     order service base URL   (default $STATEVERDICT_ORDER_URL or http://localhost:8081)
                --payment-url URL   payment service base URL (default $STATEVERDICT_PAYMENT_URL or http://localhost:8082)
                --copies N          deliveries of payment.requested; 1 = no fault (default 2)
                --deadline-ms MS    max wait for all deliveries (default 20000)
                --settle-ms MS      extra wait before reading state (default 2000)
                --trials N          repeat N times, each from clean state, and summarise (default 1)
                --report PATH       write the JSON report (or trial summary) here
              stateverdict run-schedule SCHEDULE.json [options]
                Run a schedule of steps (see scenarios/) and evaluate SV-001 to SV-004.
                Options: --order-url, --payment-url, --deadline-ms, --settle-ms, --trials, --report
              stateverdict explore [options]
                Generate valid schedules from a seed, run each once, and save any that violate a rule.
                --iterations N (default 20)  --seed S (printed; reuse to repeat)  --out DIR (default artifacts/found)
                --stop-on-first true  --report PATH  plus --order-url, --payment-url, --deadline-ms, --settle-ms
              stateverdict shrink SCHEDULE.json [options]
                Reduce a failing schedule to a locally minimal one that still fails every time.
                --trials K (default 3: runs per candidate, all must fail)  --confirm N (default 10)
                --rules SV-003 (default: rules the original violates)  --out PATH  --report PATH
                Exit: 1 minimized and fails N/N, 2 not reliably reproducible, 0 does not fail at all
              stateverdict run-saga SCHEDULE.json [options]
                Run a schedule against the external saga target (targets/piomin-saga) and evaluate
                SAGA-1 to SAGA-3. --order-url (default $STATEVERDICT_TARGET_ORDER_URL or http://localhost:8091)
                --payment-url (8092)  --stock-url (8093)  --deadline-ms (default 25000)  --trials  --report
              stateverdict replay-evidence REPORT.json
                Re-evaluate the rules on a saved report's evidence. Contacts no service.

            Exit codes: 0 PASS, 1 FAIL, 2 INCONCLUSIVE, 3 ERROR or invalid usage
              With --trials > 1: the shared verdict if all trials agree, otherwise 2
            """;

    static final int MAX_TRIALS = 1000;

    private static final List<Rule> RULES = List.of(new CaptureUniquenessRule());
    /** Every rule a schedule run evaluates; replay picks the ones a saved report used. */
    private static final List<Rule> ALL_RULES = List.of(
            new ConfirmedOrderHasCaptureRule(), new CaptureUniquenessRule(), new CancelledOrderNotConfirmedRule(),
            new RefundForCancelledCaptureRule());
    /** Rules replay may need: every rule of every runner. */
    private static final List<Rule> REPLAYABLE_RULES = java.util.stream.Stream
            .concat(ALL_RULES.stream(), SagaRunner.rules().stream()).toList();
    private static final java.util.Set<String> SAGA_OPTIONS = java.util.Set.of("--order-url", "--payment-url",
            "--stock-url", "--deadline-ms", "--trials", "--report");
    private static final java.util.Set<String> RUN_OPTIONS =
            java.util.Set.of("--order-url", "--payment-url", "--copies", "--deadline-ms", "--settle-ms", "--trials", "--report");
    private static final java.util.Set<String> EXPLORE_OPTIONS = java.util.Set.of("--order-url", "--payment-url",
            "--deadline-ms", "--settle-ms", "--iterations", "--seed", "--out", "--report", "--stop-on-first");
    private static final java.util.Set<String> SHRINK_OPTIONS = java.util.Set.of("--order-url", "--payment-url",
            "--deadline-ms", "--settle-ms", "--rules", "--trials", "--confirm", "--out", "--report");
    private static final java.util.Set<String> SCHEDULE_OPTIONS =
            java.util.Set.of("--order-url", "--payment-url", "--deadline-ms", "--settle-ms", "--trials", "--report");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ReportWriter writer = new ReportWriter(JsonMapper.builder().build());
        try {
            if (args.length >= 2 && args[0].equals("run") && args[1].equals(DuplicateDeliveryScenario.NAME)) {
                return runScenario(options(args, 2, RUN_OPTIONS), writer, out);
            }
            if (args.length >= 2 && args[0].equals("shrink")) {
                return shrink(Path.of(args[1]), options(args, 2, SHRINK_OPTIONS), out);
            }
            if (args.length >= 1 && args[0].equals("explore")) {
                return explore(options(args, 1, EXPLORE_OPTIONS), writer, out);
            }
            if (args.length >= 2 && args[0].equals("run-schedule")) {
                return runSchedule(Path.of(args[1]), options(args, 2, SCHEDULE_OPTIONS), writer, out);
            }
            if (args.length >= 2 && args[0].equals("run-saga")) {
                return runSaga(Path.of(args[1]), options(args, 2, SAGA_OPTIONS), writer, out);
            }
            if (args.length == 2 && args[0].equals("replay-evidence")) {
                return replay(Path.of(args[1]), writer, out);
            }
            err.print(USAGE);
            return USAGE_ERROR;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE);
            return USAGE_ERROR;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return USAGE_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("error: interrupted");
            return USAGE_ERROR;
        }
    }

    private static int runScenario(Map<String, String> options, ReportWriter writer, PrintStream out)
            throws IOException, InterruptedException {
        DuplicateDeliveryScenario.Config config = new DuplicateDeliveryScenario.Config(
                URI.create(options.getOrDefault("--order-url", env("STATEVERDICT_ORDER_URL", "http://localhost:8081"))),
                URI.create(options.getOrDefault("--payment-url", env("STATEVERDICT_PAYMENT_URL", "http://localhost:8082"))),
                intOption(options, "--copies", 2),
                129_900,
                Duration.ofMillis(intOption(options, "--deadline-ms", 20_000)),
                Duration.ofMillis(intOption(options, "--settle-ms", 2_000)));
        int trials = intOption(options, "--trials", 1);
        if (trials < 1 || trials > MAX_TRIALS) {
            throw new IllegalArgumentException("--trials must be between 1 and " + MAX_TRIALS);
        }
        DuplicateDeliveryScenario scenario = new DuplicateDeliveryScenario(config, RULES, JsonMapper.builder().build());
        if (trials == 1) {
            return finish(scenario.run(), options.get("--report"), writer, out);
        }
        List<RunReport> reports = new java.util.ArrayList<>();
        for (int trial = 1; trial <= trials; trial++) {
            RunReport report = scenario.run();
            out.println("trial " + trial + "/" + trials + ": " + report.verdict() + " (" + report.runId() + ")");
            reports.add(report);
        }
        String fault = config.copies() > 1 ? "payment.requested delivered " + config.copies() + " times" : "none (baseline)";
        return summarise(TrialSummary.of(DuplicateDeliveryScenario.NAME, fault, reports), options.get("--report"), writer, out);
    }

    private static int runSchedule(Path schedulePath, Map<String, String> options, ReportWriter writer, PrintStream out)
            throws IOException, InterruptedException {
        JsonMapper json = JsonMapper.builder().build();
        Schedule schedule = readSchedule(json, schedulePath);
        ScheduleRunner.Config config = scheduleConfig(options);
        int trials = intOption(options, "--trials", 1);
        if (trials < 1 || trials > MAX_TRIALS) {
            throw new IllegalArgumentException("--trials must be between 1 and " + MAX_TRIALS);
        }
        ScheduleRunner runner = new ScheduleRunner(config, ALL_RULES, json);
        out.println("Schedule: " + schedule.name());
        for (int i = 0; i < schedule.steps().size(); i++) {
            out.println("  " + (i + 1) + ". " + schedule.steps().get(i).describe());
        }
        out.println();
        if (trials == 1) {
            return finish(runner.run(schedule), options.get("--report"), writer, out);
        }
        List<RunReport> reports = new java.util.ArrayList<>();
        for (int trial = 1; trial <= trials; trial++) {
            RunReport report = runner.run(schedule);
            out.println("trial " + trial + "/" + trials + ": " + report.verdict() + " (" + report.runId() + ")");
            reports.add(report);
        }
        String fault = schedule.arms().isEmpty() ? "none" : schedule.arms().stream()
                .map(dev.stateverdict.core.schedule.Step::describe).collect(java.util.stream.Collectors.joining("; "));
        return summarise(TrialSummary.of(schedule.name(), fault, reports), options.get("--report"), writer, out);
    }

    private static int runSaga(Path schedulePath, Map<String, String> options, ReportWriter writer, PrintStream out)
            throws IOException, InterruptedException {
        JsonMapper json = JsonMapper.builder().build();
        SagaSchedule schedule;
        try {
            schedule = json.readValue(Files.readString(schedulePath), SagaSchedule.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot read saga schedule " + schedulePath + ": " + e.getMessage());
        }
        List<String> problems = schedule.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid saga schedule " + schedulePath + ": " + String.join("; ", problems));
        }
        SagaRunner.Config defaults = SagaRunner.Config.defaults(
                URI.create(options.getOrDefault("--order-url", env("STATEVERDICT_TARGET_ORDER_URL", "http://localhost:8091"))),
                URI.create(options.getOrDefault("--payment-url", env("STATEVERDICT_TARGET_PAYMENT_URL", "http://localhost:8092"))),
                URI.create(options.getOrDefault("--stock-url", env("STATEVERDICT_TARGET_STOCK_URL", "http://localhost:8093"))));
        SagaRunner.Config config = new SagaRunner.Config(defaults.orderService(), defaults.paymentService(),
                defaults.stockService(), Duration.ofMillis(intOption(options, "--deadline-ms", 25_000)),
                defaults.settleWindow(), defaults.eventualWindow());
        int trials = intOption(options, "--trials", 1);
        if (trials < 1 || trials > MAX_TRIALS) {
            throw new IllegalArgumentException("--trials must be between 1 and " + MAX_TRIALS);
        }
        SagaRunner runner = new SagaRunner(config, SagaRunner.rules(), json);
        out.println("Saga schedule: " + schedule.name());
        for (SagaSchedule.Arm arm : schedule.arms()) {
            out.println("  " + arm.describe());
        }
        SagaSchedule.Order order = schedule.order();
        out.println("  place order: price " + order.price() + " (customer funds " + order.funds() + "), count "
                + order.count() + " (product stock " + order.stock() + ")");
        out.println();
        if (trials == 1) {
            return finish(runner.run(schedule), options.get("--report"), writer, out);
        }
        List<RunReport> reports = new java.util.ArrayList<>();
        for (int trial = 1; trial <= trials; trial++) {
            RunReport report = runner.run(schedule);
            out.println("trial " + trial + "/" + trials + ": " + report.verdict() + " (" + report.runId() + ")");
            reports.add(report);
        }
        String fault = schedule.arms().isEmpty() ? "none" : schedule.arms().stream()
                .map(SagaSchedule.Arm::describe).collect(java.util.stream.Collectors.joining("; "));
        return summarise(TrialSummary.of(schedule.name(), fault, reports), options.get("--report"), writer, out);
    }

    private static int explore(Map<String, String> options, ReportWriter writer, PrintStream out)
            throws IOException, InterruptedException {
        JsonMapper json = JsonMapper.builder().build();
        ScheduleRunner runner = new ScheduleRunner(scheduleConfig(options), ALL_RULES, json);
        int iterations = intOption(options, "--iterations", 20);
        if (iterations < 1 || iterations > MAX_TRIALS) {
            throw new IllegalArgumentException("--iterations must be between 1 and " + MAX_TRIALS);
        }
        long seed = longOption(options, "--seed", System.currentTimeMillis() % 100_000);
        boolean stopOnFirst = Boolean.parseBoolean(options.getOrDefault("--stop-on-first", "false"));
        Path saveDir = Path.of(options.getOrDefault("--out", "artifacts/found"));

        out.println("Exploring " + iterations + " generated schedules, seed " + seed
                + " (rerun with --seed " + seed + " to get the same schedules)\n");
        dev.stateverdict.core.explore.ExplorationReport report = new dev.stateverdict.core.explore.Explorer(runner::run, json)
                .explore(seed, iterations, saveDir, stopOnFirst, out);
        out.print(dev.stateverdict.core.explore.Explorer.toConsole(report));
        String reportPath = options.get("--report");
        if (reportPath != null) {
            write(Path.of(reportPath), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            out.println("Report:  " + reportPath);
        }
        out.println("Exit code: " + report.overall().exitCode());
        return report.overall().exitCode();
    }

    private static int shrink(Path schedulePath, Map<String, String> options, PrintStream out)
            throws IOException, InterruptedException {
        JsonMapper json = JsonMapper.builder().build();
        Schedule schedule = readSchedule(json, schedulePath);
        int trials = intOption(options, "--trials", 3);
        int confirm = intOption(options, "--confirm", 10);
        if (trials < 1 || trials > 50 || confirm < 1 || confirm > MAX_TRIALS) {
            throw new IllegalArgumentException("--trials must be 1-50 and --confirm 1-" + MAX_TRIALS);
        }
        List<String> rules = options.containsKey("--rules")
                ? List.of(options.get("--rules").split(","))
                : List.of();
        ScheduleRunner runner = new ScheduleRunner(scheduleConfig(options), ALL_RULES, json);
        out.println("Shrinking " + schedulePath + ": each candidate must fail " + trials + "/" + trials
                + "; original and result are measured over " + confirm + " runs\n");
        dev.stateverdict.core.explore.ShrinkReport report =
                new dev.stateverdict.core.explore.Shrinker(runner::run, trials, confirm).shrink(schedule, rules, out);
        out.print(dev.stateverdict.core.explore.Shrinker.toConsole(report));
        Path minimizedPath = Path.of(options.getOrDefault("--out",
                schedulePath.toString().replaceFirst("\\.json$", "") + ".min.json"));
        if (report.status() == dev.stateverdict.core.explore.ShrinkReport.Status.MINIMIZED) {
            write(minimizedPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(report.minimized()));
            out.println("Minimized schedule: " + minimizedPath);
        }
        String reportPath = options.get("--report");
        if (reportPath != null) {
            write(Path.of(reportPath), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            out.println("Report: " + reportPath);
        }
        // 1: a reliably failing, minimized schedule (a confirmed defect); 2: not reliably reproducible.
        int exit = report.status() == dev.stateverdict.core.explore.ShrinkReport.Status.MINIMIZED
                ? (report.minimizedFailures() == report.confirmTrials() ? 1 : 2)
                : (report.originalFailures() == 0 ? 0 : 2);
        out.println("Exit code: " + exit);
        return exit;
    }

    private static Schedule readSchedule(JsonMapper json, Path path) throws IOException {
        Schedule schedule;
        try {
            schedule = json.readValue(Files.readString(path), Schedule.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("cannot read schedule " + path + ": " + e.getMessage());
        }
        List<String> problems = schedule.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid schedule " + path + ":\n  " + String.join("\n  ", problems));
        }
        return schedule;
    }

    private static ScheduleRunner.Config scheduleConfig(Map<String, String> options) {
        return new ScheduleRunner.Config(
                URI.create(options.getOrDefault("--order-url", env("STATEVERDICT_ORDER_URL", "http://localhost:8081"))),
                URI.create(options.getOrDefault("--payment-url", env("STATEVERDICT_PAYMENT_URL", "http://localhost:8082"))),
                Duration.ofMillis(intOption(options, "--deadline-ms", 20_000)),
                Duration.ofMillis(intOption(options, "--settle-ms", 2_000)));
    }

    private static long longOption(Map<String, String> options, String name, long defaultValue) {
        String value = options.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, got '" + value + "'");
        }
    }

    private static int summarise(TrialSummary summary, String reportPath, ReportWriter writer, PrintStream out)
            throws IOException {
        out.println();
        out.print(summary.toConsole());
        if (reportPath != null) {
            write(Path.of(reportPath), writer.toJson(summary));
            out.println("Report:  " + reportPath);
        }
        out.println("Exit code: " + summary.overall().exitCode());
        return summary.overall().exitCode();
    }

    private static int replay(Path reportPath, ReportWriter writer, PrintStream out) throws IOException {
        RunReport saved = writer.fromJson(Files.readString(reportPath));
        java.util.Set<String> used = saved.ruleResults().stream().map(r -> r.ruleId()).collect(java.util.stream.Collectors.toSet());
        List<Rule> rules = REPLAYABLE_RULES.stream().filter(rule -> used.contains(rule.id())).toList();
        RunReport replayed = DuplicateDeliveryScenario.replay(saved, rules, Clock.systemUTC());
        return finish(replayed, null, writer, out);
    }

    private static int finish(RunReport report, String reportPath, ReportWriter writer, PrintStream out)
            throws IOException {
        out.print(writer.toConsole(report));
        if (reportPath != null) {
            write(Path.of(reportPath), writer.toJson(report));
            out.println("Report:          " + reportPath);
        }
        out.println("Exit code:       " + report.verdict().exitCode());
        return report.verdict().exitCode();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void write(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
    }

    private static Map<String, String> options(String[] args, int from, java.util.Set<String> allowed) {
        Map<String, String> options = new HashMap<>();
        for (int i = from; i < args.length; i += 2) {
            String name = args[i];
            if (!name.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected '--option value' but got '" + name + "'");
            }
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown option '" + name + "'");
            }
            options.put(name, args[i + 1]);
        }
        return options;
    }

    private static int intOption(Map<String, String> options, String name, int defaultValue) {
        String value = options.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, got '" + value + "'");
        }
    }
}
