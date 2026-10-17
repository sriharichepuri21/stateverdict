package dev.stateverdict.core.explore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.schedule.Fault;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Service;
import dev.stateverdict.core.schedule.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReductionsTest {

    private static final Step HOLD_REQUEST = new Step.Arm(Service.PAYMENT, "payment.requested", Fault.hold());
    private static final Step DELAY_COMPLETION = new Step.Arm(Service.ORDER, "payment.completed", Fault.delay(900));
    private static final Step PLACE = new Step.PlaceOrder(129_900);
    private static final Step AWAIT_REQUEST = new Step.AwaitHeld(Service.PAYMENT, "payment.requested", 1);
    private static final Step CANCEL = new Step.Cancel();
    private static final Step RELEASE_PAYMENT = new Step.ReleaseAll(Service.PAYMENT);

    private static final Schedule FOUND = new Schedule("found",
            List.of(HOLD_REQUEST, DELAY_COMPLETION, PLACE, AWAIT_REQUEST, CANCEL, RELEASE_PAYMENT));

    @Test
    void removingAHoldAlsoRemovesItsWaitAndRelease() {
        assertThat(Reductions.of(FOUND)).anySatisfy(candidate -> {
            assertThat(candidate.change()).contains("remove arm HOLD on payment.requested");
            assertThat(candidate.schedule().steps()).containsExactly(DELAY_COMPLETION, PLACE, CANCEL);
        });
    }

    @Test
    void offersFaultRemovalCancelRemovalAndSimplificationBiggestFirst() {
        List<String> changes = Reductions.of(FOUND).stream().map(Reductions.Candidate::change).toList();

        assertThat(changes).hasSize(4);
        assertThat(changes.get(0)).startsWith("remove arm");
        assertThat(changes.get(1)).startsWith("remove arm");
        assertThat(changes.get(2)).isEqualTo("remove the customer's cancel");
        assertThat(changes.get(3)).isEqualTo("simplify DELAY 900 ms to DELAY 400 ms on payment.completed");
    }

    @Test
    void everyCandidateOfEveryGeneratedScheduleIsValidAndNoBigger() {
        ScheduleGenerator generator = new ScheduleGenerator(42);
        for (int i = 0; i < 2_000; i++) {
            Schedule schedule = generator.next();
            for (Reductions.Candidate candidate : Reductions.of(schedule)) {
                assertThat(candidate.schedule().problems()).as(candidate.change()).isEmpty();
                assertThat(Reductions.interventions(candidate.schedule())).isLessThanOrEqualTo(Reductions.interventions(schedule));
                assertThat(candidate.schedule().steps()).isNotEqualTo(schedule.steps());
            }
        }
    }

    @Test
    void waitingForAStatusCanBeDroppedOnItsOwn() {
        Schedule afterConfirmed = new Schedule("after", List.of(PLACE, new Step.AwaitOrderStatus("CONFIRMED"), CANCEL));

        assertThat(Reductions.of(afterConfirmed)).extracting(Reductions.Candidate::change)
                .containsExactly("remove the customer's cancel", "stop waiting for the order to be CONFIRMED");
    }

    @Test
    void aPlainOrderCannotBeReducedFurther() {
        assertThat(Reductions.of(new Schedule("plain", List.of(PLACE)))).isEmpty();
    }

    @Test
    void simplificationStopsAtTheSmallestParameters() {
        assertThat(Reductions.simplify(Fault.duplicate(3))).isEqualTo(Fault.duplicate(2));
        assertThat(Reductions.simplify(Fault.duplicate(2))).isNull();
        assertThat(Reductions.simplify(Fault.delay(150))).isEqualTo(Fault.delay(100));
        assertThat(Reductions.simplify(Fault.delay(100))).isNull();
        assertThat(Reductions.simplify(Fault.hold())).isNull();
    }
}
