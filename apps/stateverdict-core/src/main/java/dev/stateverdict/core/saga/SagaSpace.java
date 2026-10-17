package dev.stateverdict.core.saga;

import dev.stateverdict.core.explore.ScheduleSpace;
import dev.stateverdict.core.schedule.Fault;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The schedule space of the external saga target, for the generic explorer and shrinker.
 *
 * <h2>What the generator explores</h2>
 * <ul>
 *   <li>For each of the four messages a fault can target (payment or stock, reserve or settle):
 *       usually nothing, otherwise a sequential duplicate (2 or 3 copies), a concurrent duplicate
 *       (2 or 3), a short delay (100 to 2000 ms) or a long delay (11 to 13 s, beyond the order
 *       service's 10 s join window).</li>
 *   <li>The order: usually affordable and in stock; sometimes the customer cannot afford it, the
 *       product is short, or both.</li>
 * </ul>
 * It knows nothing about which combinations fail. The probabilities are fixed and seeded, so a
 * campaign can be repeated exactly.
 *
 * <h2>Reductions, biggest first</h2>
 * Remove a fault; make the order affordable, or in stock; then simplify a fault (fewer copies,
 * concurrent copies delivered one after another, half the delay).
 */
public final class SagaSpace implements ScheduleSpace<SagaSchedule> {

    static final int PRICE = 100;
    static final int COUNT = 1;
    static final int FUNDS = 1_000;
    static final int STOCK = 10;
    /** Above the customer's funds: the payment service rejects. */
    static final int UNAFFORDABLE_PRICE = 5_000;
    /** Above the product's stock: the stock service rejects. */
    static final int SHORT_COUNT = 20;

    record Target(SagaSchedule.Service service, String phase) {
        String label() {
            return service.name().toLowerCase() + " " + phase;
        }
    }

    static final List<Target> TARGETS = List.of(
            new Target(SagaSchedule.Service.PAYMENT, SagaSchedule.RESERVE),
            new Target(SagaSchedule.Service.STOCK, SagaSchedule.RESERVE),
            new Target(SagaSchedule.Service.PAYMENT, SagaSchedule.SETTLE),
            new Target(SagaSchedule.Service.STOCK, SagaSchedule.SETTLE));

    @Override
    public Generator<SagaSchedule> generator(long seed) {
        Random random = new Random(seed);
        int[] generated = {0};
        return () -> {
            generated[0]++;
            List<SagaSchedule.Arm> arms = new ArrayList<>();
            for (Target target : TARGETS) {
                Fault fault = randomFault(random);
                if (fault != null) {
                    arms.add(new SagaSchedule.Arm(target.service(), target.phase(), fault));
                }
            }
            int roll = random.nextInt(20);
            boolean paymentShort = roll == 0 || roll == 1 || roll == 2 || roll == 6;
            boolean stockShort = roll == 3 || roll == 4 || roll == 5 || roll == 6;
            SagaSchedule.Order order = new SagaSchedule.Order(paymentShort ? UNAFFORDABLE_PRICE : PRICE,
                    stockShort ? SHORT_COUNT : COUNT, FUNDS, STOCK);
            return new SagaSchedule("saga-s" + seed + "-" + generated[0], order, arms);
        };
    }

    /** About 60% no fault per message; the rest spread over the fault kinds. */
    private static Fault randomFault(Random random) {
        int roll = random.nextInt(20);
        if (roll < 12) {
            return null;
        }
        return switch (roll) {
            case 12, 13 -> Fault.duplicate(2 + random.nextInt(2));
            case 14, 15 -> Fault.duplicateConcurrent(2 + random.nextInt(2));
            case 16, 17, 18 -> Fault.delay(100 + 100L * random.nextInt(20));
            default -> Fault.delay(11_000 + 1_000L * random.nextInt(3));
        };
    }

    @Override
    public List<String> problems(SagaSchedule schedule) {
        return schedule.problems();
    }

    @Override
    public String name(SagaSchedule schedule) {
        return schedule.name();
    }

    @Override
    public SagaSchedule renamed(SagaSchedule schedule, String name) {
        return schedule.withArms(name, schedule.arms());
    }

    @Override
    public List<Candidate<SagaSchedule>> reductions(SagaSchedule schedule) {
        Map<SagaSchedule, Candidate<SagaSchedule>> unique = new LinkedHashMap<>();
        List<SagaSchedule.Arm> arms = schedule.arms();
        for (int i = 0; i < arms.size(); i++) {
            List<SagaSchedule.Arm> without = new ArrayList<>(arms);
            without.remove(i);
            add(unique, schedule, schedule.withArms(schedule.name(), without), "remove " + arms.get(i).describe());
        }
        SagaSchedule.Order order = schedule.order();
        if (order.price() >= order.funds()) {
            add(unique, schedule, schedule.withOrder(schedule.name(),
                    new SagaSchedule.Order(PRICE, order.count(), order.funds(), order.stock())),
                    "make the order affordable");
        }
        if (order.count() >= order.stock()) {
            add(unique, schedule, schedule.withOrder(schedule.name(),
                    new SagaSchedule.Order(order.price(), COUNT, order.funds(), order.stock())),
                    "put the product in stock");
        }
        for (int i = 0; i < arms.size(); i++) {
            SagaSchedule.Arm arm = arms.get(i);
            Fault simpler = simplify(arm.fault());
            if (simpler != null) {
                List<SagaSchedule.Arm> changed = new ArrayList<>(arms);
                changed.set(i, new SagaSchedule.Arm(arm.service(), arm.phase(), simpler));
                add(unique, schedule, schedule.withArms(schedule.name(), changed),
                        "simplify " + arm.fault() + " to " + simpler + " on " + arm.phase() + " ("
                                + arm.service().name().toLowerCase() + ")");
            }
        }
        return List.copyOf(unique.values());
    }

    private static void add(Map<SagaSchedule, Candidate<SagaSchedule>> unique, SagaSchedule original,
                            SagaSchedule candidate, String change) {
        if (!candidate.equals(original) && candidate.isValid()) {
            unique.putIfAbsent(candidate, new Candidate<>(candidate, change));
        }
    }

    /** A simpler version of the same fault, or {@code null} if it is already as simple as it gets. */
    static Fault simplify(Fault fault) {
        return switch (fault.type()) {
            case Fault.DUPLICATE -> fault.copies() > 2 ? Fault.duplicate(2) : null;
            case Fault.DUPLICATE_CONCURRENT -> fault.copies() > 2
                    ? Fault.duplicateConcurrent(2) : Fault.duplicate(fault.copies());
            case Fault.DELAY -> fault.delayMs() > 100
                    ? Fault.delay(Math.max(100, (fault.delayMs() / 2) / 100 * 100)) : null;
            default -> null;
        };
    }

    /** One per fault, plus one for each side that rejects the order. */
    @Override
    public int interventions(SagaSchedule schedule) {
        SagaSchedule.Order order = schedule.order();
        return schedule.arms().size() + (order.price() >= order.funds() ? 1 : 0) + (order.count() >= order.stock() ? 1 : 0);
    }

    @Override
    public String summary(SagaSchedule schedule) {
        List<String> parts = new ArrayList<>();
        for (SagaSchedule.Arm arm : schedule.arms()) {
            parts.add(arm.service().name().toLowerCase() + " " + arm.phase() + ":" + arm.fault());
        }
        if (parts.isEmpty()) {
            parts.add("no faults");
        }
        parts.add(orderKind(schedule.order()));
        return String.join(", ", parts);
    }

    static String orderKind(SagaSchedule.Order order) {
        boolean paymentShort = order.price() >= order.funds();
        boolean stockShort = order.count() >= order.stock();
        if (paymentShort && stockShort) {
            return "order: unaffordable and short of stock";
        }
        if (paymentShort) {
            return "order: unaffordable";
        }
        return stockShort ? "order: short of stock" : "order: affordable, in stock";
    }

    @Override
    public List<String> steps(SagaSchedule schedule) {
        List<String> steps = new ArrayList<>();
        schedule.arms().forEach(arm -> steps.add(arm.describe()));
        SagaSchedule.Order order = schedule.order();
        steps.add("place order: price " + order.price() + " (funds " + order.funds() + "), count " + order.count()
                + " (stock " + order.stock() + ")");
        return steps;
    }

    @Override
    public void recordCoverage(SagaSchedule schedule, Map<String, Integer> coverage) {
        for (Target target : TARGETS) {
            String kind = schedule.arms().stream()
                    .filter(arm -> arm.service() == target.service() && arm.phase().equals(target.phase()))
                    .map(arm -> Fault.DELAY.equals(arm.fault().type())
                            ? (arm.fault().delayMs() > 10_000 ? "DELAY > 10 s" : "DELAY <= 2 s")
                            : arm.fault().type())
                    .findFirst().orElse("none");
            coverage.merge(target.label() + " " + kind, 1, Integer::sum);
        }
        coverage.merge(orderKind(schedule.order()), 1, Integer::sum);
    }
}
