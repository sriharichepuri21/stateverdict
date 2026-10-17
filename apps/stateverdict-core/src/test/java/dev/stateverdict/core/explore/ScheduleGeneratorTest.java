package dev.stateverdict.core.explore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Step;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {

    private static List<Schedule> generate(long seed, int count) {
        ScheduleGenerator generator = new ScheduleGenerator(seed);
        return IntStream.range(0, count).mapToObj(i -> generator.next()).toList();
    }

    @Test
    void everyGeneratedScheduleIsValid() {
        for (long seed : new long[] {1, 42, 7_777}) {
            for (Schedule schedule : generate(seed, 1_000)) {
                assertThat(schedule.problems()).as(schedule.name()).isEmpty();
            }
        }
    }

    @Test
    void theSameSeedProducesTheSameSchedules() {
        assertThat(generate(42, 50)).isEqualTo(generate(42, 50));
    }

    @Test
    void differentSeedsProduceDifferentSchedules() {
        assertThat(generate(1, 50)).isNotEqualTo(generate(2, 50));
    }

    @Test
    void theWholeSpaceIsReachable() {
        Set<String> seen = new HashSet<>();
        for (Schedule schedule : generate(42, 500)) {
            schedule.arms().forEach(arm -> seen.add(arm.topic() + " " + arm.fault().type()));
            if (schedule.arms().isEmpty()) {
                seen.add("no faults");
            }
            seen.add(Explorer.summary(schedule).replaceAll(".*, ", ""));
            schedule.steps().stream().filter(Step.PlaceOrder.class::isInstance).map(Step.PlaceOrder.class::cast)
                    .forEach(place -> seen.add("amount " + place.amountCents()));
        }
        assertThat(seen).contains(
                "payment.requested HOLD", "payment.requested DUPLICATE", "payment.requested DELAY",
                "payment.requested DUPLICATE_CONCURRENT",
                "payment.completed HOLD", "payment.completed DUPLICATE", "payment.completed DELAY",
                "payment.completed DUPLICATE_CONCURRENT",
                "no faults", "no cancel", "cancel right after order",
                "cancel while payment.requested held", "cancel while payment.completed held",
                "cancel after order CONFIRMED",
                "amount " + ScheduleGenerator.APPROVED_AMOUNT, "amount " + ScheduleGenerator.DECLINED_AMOUNT);
    }

    @Test
    void everyHoldIsAwaitedBeforeItIsReleased() {
        for (Schedule schedule : generate(3, 1_000)) {
            for (Step.Arm hold : schedule.arms()) {
                if (!hold.fault().type().equals("HOLD")) {
                    continue;
                }
                int await = indexOfAwait(schedule.steps(), hold.topic());
                int release = schedule.steps().indexOf(new Step.ReleaseAll(hold.service()));
                assertThat(await).as(schedule.name()).isGreaterThanOrEqualTo(0).isLessThan(release);
            }
        }
    }

    @Test
    void heldPaymentRequestsAreReleasedBeforeWaitingForTheOrderServiceHold() {
        for (Schedule schedule : generate(9, 1_000)) {
            List<Step> steps = schedule.steps();
            int releasePayment = steps.indexOf(new Step.ReleaseAll(dev.stateverdict.core.schedule.Service.PAYMENT));
            int awaitOrder = indexOfAwait(steps, "payment.completed");
            if (releasePayment >= 0 && awaitOrder >= 0) {
                assertThat(releasePayment).as(schedule.name()).isLessThan(awaitOrder);
            }
        }
    }

    private static int indexOfAwait(List<Step> steps, String topic) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.AwaitHeld await && await.topic().equals(topic)) {
                return i;
            }
        }
        return -1;
    }
}
