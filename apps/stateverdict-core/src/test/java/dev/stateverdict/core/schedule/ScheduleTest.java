package dev.stateverdict.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScheduleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Step HOLD_COMPLETION = new Step.Arm(Service.ORDER, "payment.completed", Fault.hold());
    private static final Step PLACE = new Step.PlaceOrder(129_900);
    private static final Step AWAIT_COMPLETION = new Step.AwaitHeld(Service.ORDER, "payment.completed", 1);
    private static final Step CANCEL = new Step.Cancel();
    private static final Step RELEASE_ORDER = new Step.ReleaseAll(Service.ORDER);

    private static Schedule schedule(Step... steps) {
        return new Schedule("test", List.of(steps));
    }

    @Test
    void theCancelRaceScheduleIsValid() {
        assertThat(schedule(HOLD_COMPLETION, PLACE, AWAIT_COMPLETION, CANCEL, RELEASE_ORDER).problems()).isEmpty();
    }

    @Test
    void aPlainOrderIsValid() {
        assertThat(schedule(PLACE).isValid()).isTrue();
    }

    @Test
    void exactlyOnePlaceOrderIsRequired() {
        assertThat(schedule(CANCEL).problems()).anyMatch(p -> p.contains("exactly one PLACE_ORDER"));
        assertThat(schedule(PLACE, PLACE).problems()).anyMatch(p -> p.contains("only one PLACE_ORDER"));
    }

    @Test
    void faultsMustBeArmedBeforeTheOrderIsPlaced() {
        assertThat(schedule(PLACE, new Step.Arm(Service.PAYMENT, "payment.requested", Fault.duplicate(2))).problems())
                .anyMatch(p -> p.contains("ARM must come before PLACE_ORDER"));
    }

    @Test
    void aHoldMustBeReleasedLater() {
        assertThat(schedule(HOLD_COMPLETION, PLACE).problems()).anyMatch(p -> p.contains("needs a later RELEASE_ALL"));
        assertThat(schedule(HOLD_COMPLETION, PLACE, new Step.ReleaseAll(Service.PAYMENT)).isValid()).isFalse();
    }

    @Test
    void aHoldMustBeAwaitedBeforeItIsReleased() {
        assertThat(schedule(HOLD_COMPLETION, PLACE, RELEASE_ORDER).problems())
                .anyMatch(p -> p.contains("needs an earlier AWAIT_HELD"));
        assertThat(schedule(HOLD_COMPLETION, PLACE, CANCEL, AWAIT_COMPLETION, RELEASE_ORDER).isValid()).isTrue();
        assertThat(schedule(HOLD_COMPLETION, PLACE, RELEASE_ORDER, AWAIT_COMPLETION, RELEASE_ORDER).isValid()).isFalse();
    }

    @Test
    void awaitingAHeldMessageNeedsAMatchingHold() {
        assertThat(schedule(PLACE, AWAIT_COMPLETION).problems()).anyMatch(p -> p.contains("AWAIT_HELD count"));
        Step wrongTopic = new Step.AwaitHeld(Service.ORDER, "payment.requested", 1);
        assertThat(schedule(HOLD_COMPLETION, PLACE, wrongTopic, RELEASE_ORDER).isValid()).isFalse();
        Step tooMany = new Step.AwaitHeld(Service.ORDER, "payment.completed", 2);
        assertThat(schedule(HOLD_COMPLETION, PLACE, tooMany, RELEASE_ORDER).isValid()).isFalse();
    }

    @Test
    void cancelOnlyOnceAndOnlyAfterPlacing() {
        assertThat(schedule(CANCEL, PLACE).problems()).anyMatch(p -> p.contains("CANCEL must come after"));
        assertThat(schedule(PLACE, CANCEL, CANCEL).problems()).anyMatch(p -> p.contains("only one CANCEL"));
    }

    @Test
    void invalidFaultsAreReported() {
        Step badDuplicate = new Step.Arm(Service.PAYMENT, "payment.requested", Fault.duplicate(1));
        Step badDelay = new Step.Arm(Service.PAYMENT, "payment.requested", Fault.delay(0));
        Step unknown = new Step.Arm(Service.PAYMENT, "payment.requested", new Fault("EXPLODE", null, null));

        assertThat(schedule(badDuplicate, PLACE).problems()).anyMatch(p -> p.contains("copies >= 2"));
        assertThat(schedule(badDelay, PLACE).problems()).anyMatch(p -> p.contains("delayMs > 0"));
        assertThat(schedule(unknown, PLACE).problems()).anyMatch(p -> p.contains("unknown fault type"));
    }

    @Test
    void schedulesRoundTripThroughJson() {
        Schedule original = schedule(HOLD_COMPLETION,
                new Step.Arm(Service.PAYMENT, "payment.requested", Fault.delay(250)), PLACE, AWAIT_COMPLETION, CANCEL,
                RELEASE_ORDER);

        Schedule read = JSON.readValue(JSON.writeValueAsString(original), Schedule.class);

        assertThat(read).isEqualTo(original);
    }

    @Test
    void theExampleSchedulesInTheRepositoryAreValid() throws Exception {
        Path dir = Path.of("../../scenarios");
        try (var files = Files.list(dir)) {
            List<Path> schedules = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(schedules).isNotEmpty();
            for (Path file : schedules) {
                Schedule schedule = JSON.readValue(Files.readString(file), Schedule.class);
                assertThat(schedule.problems()).as(file.toString()).isEmpty();
            }
        }
    }
}
