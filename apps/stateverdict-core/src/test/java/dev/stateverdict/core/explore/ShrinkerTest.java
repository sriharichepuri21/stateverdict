package dev.stateverdict.core.explore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.schedule.Fault;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Service;
import dev.stateverdict.core.schedule.Step;
import dev.stateverdict.core.verdict.EvaluationStatus;
import dev.stateverdict.core.verdict.ExecutionStatus;
import dev.stateverdict.core.verdict.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The shrinker against fake executors whose failure conditions are known exactly. */
class ShrinkerTest {

    private static final Schedule COMPLEX = new Schedule("found", List.of(
            new Step.Arm(Service.PAYMENT, "payment.requested", Fault.duplicate(3)),
            new Step.Arm(Service.ORDER, "payment.completed", Fault.hold()),
            new Step.PlaceOrder(129_900),
            new Step.AwaitHeld(Service.ORDER, "payment.completed", 1),
            new Step.Cancel(),
            new Step.ReleaseAll(Service.ORDER)));

    private int executions;

    private ScheduleExecutor failsWhen(String rule, Predicate<Schedule> condition) {
        return schedule -> {
            executions++;
            return report(schedule, rule, condition.test(schedule));
        };
    }

    private static RunReport report(Schedule schedule, String rule, boolean violated) {
        Instant now = Instant.parse("2026-10-16T00:00:00Z");
        RuleResult result = new RuleResult(rule, 1, "d",
                violated ? EvaluationStatus.VIOLATED : EvaluationStatus.SATISFIED, "e", "o", "r", List.of());
        return new RunReport(RunReport.VERSION, "r", schedule.name(), 1, List.of(), ExecutionStatus.COMPLETED,
                List.of(result), violated ? Verdict.FAIL : Verdict.PASS, List.of(), List.of(), now, now);
    }

    private static boolean hasArm(Schedule schedule, String topic, String type) {
        return schedule.arms().stream().anyMatch(arm -> arm.topic().equals(topic) && arm.fault().type().equals(type));
    }

    @Test
    void reducesToExactlyWhatTheFailureNeeds() throws Exception {
        // The defect: a cancel while payment.completed is held.
        ScheduleExecutor executor = failsWhen("SV-003",
                s -> s.cancels() && hasArm(s, "payment.completed", Fault.HOLD));

        ShrinkReport report = new Shrinker(executor, 3, 10).shrink(COMPLEX, List.of(), null);

        assertThat(report.status()).isEqualTo(ShrinkReport.Status.MINIMIZED);
        assertThat(report.targetRules()).containsExactly("SV-003");
        assertThat(report.minimized().steps()).containsExactly(
                new Step.Arm(Service.ORDER, "payment.completed", Fault.hold()),
                new Step.PlaceOrder(129_900),
                new Step.AwaitHeld(Service.ORDER, "payment.completed", 1),
                new Step.Cancel(),
                new Step.ReleaseAll(Service.ORDER));
        assertThat(report.accepted()).containsExactly("remove arm DUPLICATE x3 on payment.requested (payment)");
        assertThat(report.originalFailures()).isEqualTo(10);
        assertThat(report.minimizedFailures()).isEqualTo(10);
        assertThat(report.minimized().isValid()).isTrue();
    }

    @Test
    void simplifiesParametersAsWellAsRemovingSteps() throws Exception {
        // The defect: any duplicate delivery of the payment request.
        ScheduleExecutor executor = failsWhen("SV-002", s -> hasArm(s, "payment.requested", Fault.DUPLICATE));

        ShrinkReport report = new Shrinker(executor, 3, 10).shrink(COMPLEX, List.of(), null);

        assertThat(report.minimized().steps()).containsExactly(
                new Step.Arm(Service.PAYMENT, "payment.requested", Fault.duplicate(2)),
                new Step.PlaceOrder(129_900));
        assertThat(Reductions.interventions(report.minimized())).isEqualTo(1);
        assertThat(Reductions.interventions(report.original())).isEqualTo(3);
    }

    @Test
    void aFailureThatDoesNotReproduceEveryTimeIsMeasuredButNotShrunk() throws Exception {
        int[] call = {0};
        ScheduleExecutor flaky = schedule -> report(schedule, "SV-003", call[0]++ % 3 != 0); // fails 2 of 3

        ShrinkReport report = new Shrinker(flaky, 3, 9).shrink(COMPLEX, List.of(), null);

        assertThat(report.status()).isEqualTo(ShrinkReport.Status.UNRELIABLE);
        assertThat(report.originalFailures()).isEqualTo(6);
        assertThat(report.minimized()).isEqualTo(COMPLEX);
        assertThat(report.candidatesTried()).isZero();
    }

    @Test
    void aCandidateIsRejectedAtItsFirstPassingRun() throws Exception {
        ScheduleExecutor executor = failsWhen("SV-003", s -> s.cancels() && hasArm(s, "payment.completed", Fault.HOLD));

        ShrinkReport report = new Shrinker(executor, 5, 10).shrink(COMPLEX, List.of(), null);

        // 10 original + accepted candidate 5 + rejected candidates 1 each + final 10
        assertThat(report.runs()).isEqualTo(executions);
        assertThat(report.runs()).isLessThan(10 + report.candidatesTried() * 5 + 10);
    }

    @Test
    void onlyTheRequestedRuleCounts() throws Exception {
        ScheduleExecutor executor = failsWhen("SV-002", s -> true);

        ShrinkReport report = new Shrinker(executor, 3, 5).shrink(COMPLEX, List.of("SV-003"), null);

        assertThat(report.status()).isEqualTo(ShrinkReport.Status.UNRELIABLE);
        assertThat(report.originalFailures()).isZero();
    }

    @Test
    void consoleOutputShowsBeforeAfterAndWhatWasRemoved() throws Exception {
        ScheduleExecutor executor = failsWhen("SV-003", s -> s.cancels() && hasArm(s, "payment.completed", Fault.HOLD));

        String console = Shrinker.toConsole(new Shrinker(executor, 3, 10).shrink(COMPLEX, List.of(), null));

        assertThat(console).contains("MINIMIZED", "Original (3 interventions, fails 10/10)",
                "Locally minimal (2 interventions, fails 10/10)", "remove arm DUPLICATE x3");
    }
}
