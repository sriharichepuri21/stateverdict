package dev.stateverdict.core.explore;

import dev.stateverdict.core.schedule.Fault;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Service;
import dev.stateverdict.core.schedule.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates random but <em>valid</em> schedules from a seed. The same seed always yields the same
 * sequence of schedules, so a campaign can be repeated exactly.
 *
 * <h2>The space it explores</h2>
 * <ul>
 *   <li>For each fault target (the payment service consuming {@code payment.requested}, the order
 *       service consuming {@code payment.completed}): no fault, {@code DUPLICATE} (2 or 3 copies, one after
 *       another), {@code DUPLICATE_CONCURRENT} (2 or 3 copies at the same moment), {@code DELAY}
 *       (100 to 1500 ms) or {@code HOLD}.</li>
 *   <li>The customer: does not cancel, cancels right after placing the order, cancels while a held
 *       message is parked (only when a {@code HOLD} was chosen), or cancels after the order is
 *       CONFIRMED (only for approved amounts).</li>
 *   <li>The amount: usually approved, sometimes above the decline limit.</li>
 * </ul>
 *
 * <p>Schedules are built in a fixed shape so they are valid by construction: faults, then the order,
 * then for each held target "await until held, cancel if chosen here, release". The payment target
 * comes first because the order service's message cannot exist until the payment service's has
 * been released. Waiting before every release matters: releasing before the message is held
 * releases nothing and leaves it held forever (found by the first CI run of the explorer).
 * Generation is deliberately simple; it does not try to be clever about which schedules are likely
 * to fail. That is the point: nobody tells it where the bugs are.
 */
public final class ScheduleGenerator {

    public static final long APPROVED_AMOUNT = 129_900;
    public static final long DECLINED_AMOUNT = 600_000;

    record Target(Service service, String topic) {
    }

    static final Target PAYMENT_REQUESTED = new Target(Service.PAYMENT, "payment.requested");
    static final Target PAYMENT_COMPLETED = new Target(Service.ORDER, "payment.completed");
    static final List<Target> TARGETS = List.of(PAYMENT_REQUESTED, PAYMENT_COMPLETED);

    private final long seed;
    private final Random random;
    private int generated;

    public ScheduleGenerator(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public Schedule next() {
        generated++;
        Fault requested = randomFault();
        Fault completed = randomFault();
        long amount = random.nextInt(5) == 0 ? DECLINED_AMOUNT : APPROVED_AMOUNT;

        List<Target> holdTargets = new ArrayList<>();
        if (isHold(requested)) {
            holdTargets.add(PAYMENT_REQUESTED);
        }
        if (isHold(completed)) {
            holdTargets.add(PAYMENT_COMPLETED);
        }

        // 0 = no cancel, 1 = cancel right after placing, 2 = cancel while a chosen target is held,
        // 3 = cancel after the order is CONFIRMED
        List<Integer> modes = new ArrayList<>(List.of(0, 1));
        if (!holdTargets.isEmpty()) {
            modes.add(2);
        }
        if (amount == APPROVED_AMOUNT) {
            modes.add(3);
        }
        int cancelMode = modes.get(random.nextInt(modes.size()));
        Target cancelWhileHeld = cancelMode == 2 ? holdTargets.get(random.nextInt(holdTargets.size())) : null;

        List<Step> steps = new ArrayList<>();
        if (requested != null) {
            steps.add(new Step.Arm(PAYMENT_REQUESTED.service(), PAYMENT_REQUESTED.topic(), requested));
        }
        if (completed != null) {
            steps.add(new Step.Arm(PAYMENT_COMPLETED.service(), PAYMENT_COMPLETED.topic(), completed));
        }
        steps.add(new Step.PlaceOrder(amount));
        if (cancelMode == 1) {
            steps.add(new Step.Cancel());
        }
        for (Target target : holdTargets) {
            steps.add(new Step.AwaitHeld(target.service(), target.topic(), 1));
            if (target.equals(cancelWhileHeld)) {
                steps.add(new Step.Cancel());
            }
            steps.add(new Step.ReleaseAll(target.service()));
        }
        if (cancelMode == 3) {
            steps.add(new Step.AwaitOrderStatus("CONFIRMED"));
            steps.add(new Step.Cancel());
        }
        return new Schedule("explore-s" + seed + "-" + generated, steps);
    }

    /** How many schedules this generator has produced. */
    public int generated() {
        return generated;
    }

    private Fault randomFault() {
        return switch (random.nextInt(6)) {
            case 0, 1 -> null;
            case 2 -> Fault.duplicate(2 + random.nextInt(2));
            case 3 -> Fault.duplicateConcurrent(2 + random.nextInt(2));
            case 4 -> Fault.delay(100 + 100L * random.nextInt(15));
            default -> Fault.hold();
        };
    }

    private static boolean isHold(Fault fault) {
        return fault != null && Fault.HOLD.equals(fault.type());
    }
}
