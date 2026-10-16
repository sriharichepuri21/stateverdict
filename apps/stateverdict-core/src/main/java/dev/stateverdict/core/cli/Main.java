package dev.stateverdict.core.cli;

import dev.stateverdict.core.report.ReportWriter;
import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.CaptureUniquenessRule;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.runner.DuplicateDeliveryScenario;
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
 *                  [--copies N] [--deadline-ms MS] [--settle-ms MS] [--report PATH]
 * stateverdict replay-evidence REPORT.json
 * </pre>
 *
 * Exit codes: 0 PASS, 1 FAIL, 2 INCONCLUSIVE, 3 ERROR or invalid usage (spec section 8).
 */
public final class Main {

    static final int USAGE_ERROR = 3;

    private static final String USAGE = """
            Usage:
              stateverdict run checkout-duplicate-payment-request [options]
                --order-url URL     order service base URL   (default http://localhost:8081)
                --payment-url URL   payment service base URL (default http://localhost:8082)
                --copies N          deliveries of payment.requested; 1 = no fault (default 2)
                --deadline-ms MS    max wait for all deliveries (default 20000)
                --settle-ms MS      extra wait before reading state (default 2000)
                --report PATH       write the JSON report here
              stateverdict replay-evidence REPORT.json
                Re-evaluate the rules on a saved report's evidence. Contacts no service.

            Exit codes: 0 PASS, 1 FAIL, 2 INCONCLUSIVE, 3 ERROR or invalid usage
            """;

    private static final List<Rule> RULES = List.of(new CaptureUniquenessRule());
    private static final java.util.Set<String> RUN_OPTIONS =
            java.util.Set.of("--order-url", "--payment-url", "--copies", "--deadline-ms", "--settle-ms", "--report");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ReportWriter writer = new ReportWriter(JsonMapper.builder().build());
        try {
            if (args.length >= 2 && args[0].equals("run") && args[1].equals(DuplicateDeliveryScenario.NAME)) {
                return runScenario(options(args, 2), writer, out);
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
                URI.create(options.getOrDefault("--order-url", "http://localhost:8081")),
                URI.create(options.getOrDefault("--payment-url", "http://localhost:8082")),
                intOption(options, "--copies", 2),
                129_900,
                Duration.ofMillis(intOption(options, "--deadline-ms", 20_000)),
                Duration.ofMillis(intOption(options, "--settle-ms", 2_000)));
        RunReport report = new DuplicateDeliveryScenario(config, RULES, JsonMapper.builder().build()).run();
        return finish(report, options.get("--report"), writer, out);
    }

    private static int replay(Path reportPath, ReportWriter writer, PrintStream out) throws IOException {
        RunReport saved = writer.fromJson(Files.readString(reportPath));
        RunReport replayed = DuplicateDeliveryScenario.replay(saved, RULES, Clock.systemUTC());
        return finish(replayed, null, writer, out);
    }

    private static int finish(RunReport report, String reportPath, ReportWriter writer, PrintStream out)
            throws IOException {
        out.print(writer.toConsole(report));
        if (reportPath != null) {
            Path path = Path.of(reportPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, writer.toJson(report));
            out.println("Report:          " + path);
        }
        out.println("Exit code:       " + report.verdict().exitCode());
        return report.verdict().exitCode();
    }

    private static Map<String, String> options(String[] args, int from) {
        Map<String, String> options = new HashMap<>();
        for (int i = from; i < args.length; i += 2) {
            String name = args[i];
            if (!name.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected '--option value' but got '" + name + "'");
            }
            if (!RUN_OPTIONS.contains(name)) {
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
