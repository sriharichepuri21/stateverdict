package dev.stateverdict.core.saga;

import dev.stateverdict.core.schedule.Fault;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A run against the external saga target (piomin/sample-spring-kafka-microservices, see
 * {@code targets/piomin-saga}): one order, a fresh customer and product, and faults for the
 * messages the payment and stock services consume.
 *
 * <p>Each of those services consumes the {@code orders} topic twice per order: once to reserve
 * ({@code orders.reserve}, the NEW order) and once to settle ({@code orders.settle}, the decision:
 * CONFIRMED, REJECTED or ROLLBACK). The adapter labels the two phases so a fault can target either.
 *
 * @param order what is ordered, and what the customer and product start with
 * @param arms  at most one fault per service and phase
 */
public record SagaSchedule(String name, Order order, List<Arm> arms) {

    public static final String RESERVE = "orders.reserve";
    public static final String SETTLE = "orders.settle";

    public enum Service { PAYMENT, STOCK }

    /**
     * @param price  charged to the customer; payment accepts only if {@code price < funds}
     * @param count  items taken from the product; stock accepts only if {@code count < stock}
     * @param funds  the fresh customer's starting money
     * @param stock  the fresh product's starting items
     */
    public record Order(int price, int count, int funds, int stock) {
    }

    public record Arm(Service service, String phase, Fault fault) {
        public String describe() {
            return "arm " + fault + " on " + phase + " (" + (service == null ? "?" : service.name().toLowerCase()) + ")";
        }
    }

    public SagaSchedule {
        arms = arms == null ? List.of() : List.copyOf(arms);
    }

    public SagaSchedule withArms(String newName, List<Arm> newArms) {
        return new SagaSchedule(newName, order, newArms);
    }

    public SagaSchedule withOrder(String newName, Order newOrder) {
        return new SagaSchedule(newName, newOrder, arms);
    }

    /** Problems with this schedule, empty if valid. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("name is required");
        }
        if (order == null) {
            problems.add("order is required");
        } else if (order.price() <= 0 || order.count() <= 0 || order.funds() <= 0 || order.stock() <= 0) {
            problems.add("order price, count, funds and stock must all be positive");
        }
        Set<String> targets = new HashSet<>();
        for (Arm arm : arms) {
            if (arm.service() == null) {
                problems.add("arm service is required (PAYMENT or STOCK)");
            }
            if (!RESERVE.equals(arm.phase()) && !SETTLE.equals(arm.phase())) {
                problems.add("arm phase must be " + RESERVE + " or " + SETTLE + ", got '" + arm.phase() + "'");
            }
            if (arm.fault() == null) {
                problems.add("arm fault is required");
                continue;
            }
            problems.addAll(arm.fault().problems());
            if (Fault.HOLD.equals(arm.fault().type())) {
                problems.add("HOLD is not supported against this target (it has no release step); use DELAY");
            }
            if (!targets.add(arm.service() + " " + arm.phase())) {
                problems.add("at most one fault per service and phase: " + arm.describe());
            }
        }
        return problems;
    }

    public boolean isValid() {
        return problems().isEmpty();
    }
}
