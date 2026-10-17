package dev.stateverdict.core.explore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Step;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The exploration loop against a fake executor that "fails" exactly the schedules modelled on the
 * real defect: a customer cancel while payment.completed is held.
 */
class ExplorerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private final List<Schedule> executed = new ArrayList<>();

    private RunReport fakeRun(Schedule schedule) {
        executed.add(schedule);
        boolean violates = Explorer.summary(schedule).contains("cancel while payment.completed held");
        RuleResult rule = new RuleResult("SV-003", 1, "d",
                violates ? EvaluationStatus.VIOLATED : EvaluationStatus.SATISFIED, "e", "o", "r", List.of());
        Instant now = Instant.parse("2026-10-16T00:00:00Z");
        return new RunReport(RunReport.VERSION, "run-" + executed.size(), schedule.name(), 1, List.of(),
                ExecutionStatus.COMPLETED, List.of(rule), violates ? Verdict.FAIL : Verdict.PASS, List.of(), List.of(),
                now, now);
    }

    @Test
    void findsAndSavesViolatingSchedulesThatNobodyWroteByHand() throws Exception {
        ExplorationReport<Schedule> report = new Explorer<>(new CommerceSpace(), this::fakeRun, JSON).explore(42, 200, dir, false, null);

        assertThat(report.findings()).isNotEmpty();
        assertThat(report.overall()).isEqualTo(Verdict.FAIL);
        assertThat(report.count(Verdict.FAIL)).isEqualTo(report.findings().size());
        for (ExplorationReport.Finding<Schedule> finding : report.findings()) {
            assertThat(finding.violatedRules()).containsExactly("SV-003");
            Path saved = Path.of(finding.savedAs());
            assertThat(saved).exists();
            Schedule reread = JSON.readValue(Files.readString(saved), Schedule.class);
            assertThat(reread).isEqualTo(finding.schedule());
            assertThat(reread.steps()).contains(new Step.Cancel());
        }
    }

    @Test
    void theSameSeedExploresTheSameSchedules() throws Exception {
        new Explorer<>(new CommerceSpace(), this::fakeRun, JSON).explore(5, 30, null, false, null);
        List<Schedule> first = List.copyOf(executed);
        executed.clear();

        new Explorer<>(new CommerceSpace(), this::fakeRun, JSON).explore(5, 30, null, false, null);

        assertThat(executed).isEqualTo(first);
    }

    @Test
    void stopOnFirstEndsAtTheFirstFinding() throws Exception {
        ExplorationReport<Schedule> report = new Explorer<>(new CommerceSpace(), this::fakeRun, JSON).explore(42, 500, null, true, null);

        assertThat(report.findings()).hasSize(1);
        assertThat(report.iterations()).isEqualTo(report.findings().getFirst().iteration());
    }

    @Test
    void aCampaignWithoutViolationsPassesAndReportsCoverage() throws Exception {
        ExplorationReport<Schedule> report = new Explorer<Schedule>(new CommerceSpace(), schedule -> {
            Instant now = Instant.now();
            RuleResult ok = new RuleResult("SV-003", 1, "d", EvaluationStatus.SATISFIED, "e", "o", "r", List.of());
            return new RunReport(RunReport.VERSION, "r", schedule.name(), 1, List.of(), ExecutionStatus.COMPLETED,
                    List.of(ok), Verdict.PASS, List.of(), List.of(), now, now);
        }, JSON).explore(42, 40, null, false, null);

        assertThat(report.overall()).isEqualTo(Verdict.PASS);
        assertThat(report.findings()).isEmpty();
        assertThat(report.coverage().values().stream().mapToInt(Integer::intValue).sum()).isPositive();
        assertThat(report.coverage()).containsKeys("amount: approved", "customer: no cancel");
    }

    @Test
    void progressAndSummaryNameTheFindings() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ExplorationReport<Schedule> report = new Explorer<>(new CommerceSpace(), this::fakeRun, JSON)
                .explore(42, 200, dir, false, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("violates SV-003");
        assertThat(new Explorer<>(new CommerceSpace(), this::fakeRun, JSON).toConsole(report)).contains("Findings: " + report.findings().size(), "Coverage", "Overall: FAIL");
        assertThat(Explorer.violatedRules(report)).containsExactly("SV-003");
    }
}
