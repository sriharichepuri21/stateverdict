package dev.stateverdict.core.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void noArgumentsPrintsUsageAndExitsWithThree() {
        assertThat(run()).isEqualTo(Main.USAGE_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage:");
    }

    @Test
    void unknownScenarioIsAUsageError() {
        assertThat(run("run", "no-such-scenario")).isEqualTo(Main.USAGE_ERROR);
    }

    @Test
    void unknownOptionIsRejected() {
        assertThat(run("run", "checkout-duplicate-payment-request", "--colour", "blue")).isEqualTo(Main.USAGE_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown option '--colour'");
    }

    @Test
    void nonNumericCopiesIsRejected() {
        assertThat(run("run", "checkout-duplicate-payment-request", "--copies", "two")).isEqualTo(Main.USAGE_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--copies must be a whole number");
    }

    @Test
    void trialsOutOfRangeAreRejected() {
        assertThat(run("run", "checkout-duplicate-payment-request", "--trials", "0")).isEqualTo(Main.USAGE_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--trials must be between 1 and");
    }

    @Test
    void missingReportFileForReplayIsAnError() {
        assertThat(run("replay-evidence", "does-not-exist.json")).isEqualTo(Main.USAGE_ERROR);
    }
}
