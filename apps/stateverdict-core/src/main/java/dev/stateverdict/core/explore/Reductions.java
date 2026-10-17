package dev.stateverdict.core.explore;

import dev.stateverdict.core.schedule.Fault;
import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Service;
import dev.stateverdict.core.schedule.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ways a schedule can be made smaller or simpler, each one keeping the schedule valid.
 *
 * <p>Removing a step can leave other steps without purpose: removing a {@code HOLD} leaves its
 * {@code AWAIT_HELD} waiting for something that will never be held, and maybe a {@code RELEASE_ALL}
 * with nothing to release. Those dependants are removed with it ("dependency-aware"). Any candidate
 * that is still invalid afterwards is discarded, so the shrinker never runs an invalid schedule.
 *
 * <p>Candidates are ordered from the biggest reduction to the smallest: remove a whole fault, remove
 * the customer's cancel, stop waiting for an order status, then simplify a fault (fewer duplicate
 * copies, concurrent copies delivered one after another instead, shorter delay). If the shrinker
 * keeps a concurrent duplicate, the sequential version did not reproduce the failure: the race is
 * what matters.
 */
public final class Reductions {

    private Reductions() {
    }

    /** A smaller candidate and a description of what was taken away. */
    public record Candidate(Schedule schedule, String change) {
    }

    public static List<Candidate> of(Schedule schedule) {
        Map<List<Step>, Candidate> unique = new LinkedHashMap<>();
        List<Step> steps = schedule.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.Arm arm) {
                add(unique, schedule, removeArm(steps, i), "remove " + arm.describe());
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.Cancel) {
                List<Step> without = new ArrayList<>(steps);
                without.remove(i);
                add(unique, schedule, without, "remove the customer's cancel");
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.AwaitOrderStatus await) {
                List<Step> without = new ArrayList<>(steps);
                without.remove(i);
                add(unique, schedule, without, "stop waiting for the order to be " + await.status());
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.Arm arm) {
                Fault simpler = simplify(arm.fault());
                if (simpler != null) {
                    List<Step> changed = new ArrayList<>(steps);
                    changed.set(i, new Step.Arm(arm.service(), arm.topic(), simpler));
                    add(unique, schedule, changed, "simplify " + arm.fault() + " to " + simpler + " on " + arm.topic());
                }
            }
        }
        return List.copyOf(unique.values());
    }

    /** A rough size used to report how much was removed: one per fault, one for a cancel. */
    public static int interventions(Schedule schedule) {
        return schedule.arms().size() + (schedule.cancels() ? 1 : 0);
    }

    private static void add(Map<List<Step>, Candidate> unique, Schedule original, List<Step> steps, String change) {
        Schedule candidate = original.withSteps(original.name(), List.copyOf(steps));
        if (!candidate.steps().equals(original.steps()) && candidate.isValid()) {
            unique.putIfAbsent(candidate.steps(), new Candidate(candidate, change));
        }
    }

    /** Removes the arm at {@code index} and any step that only existed because of it. */
    private static List<Step> removeArm(List<Step> steps, int index) {
        Step.Arm removed = (Step.Arm) steps.get(index);
        List<Step> result = new ArrayList<>(steps);
        result.remove(index);
        if (!Fault.HOLD.equals(removed.fault().type())) {
            return result;
        }
        boolean anotherHoldOnTopic = result.stream().anyMatch(step -> step instanceof Step.Arm arm
                && arm.service() == removed.service() && arm.topic().equals(removed.topic())
                && Fault.HOLD.equals(arm.fault().type()));
        if (!anotherHoldOnTopic) {
            result.removeIf(step -> step instanceof Step.AwaitHeld await
                    && await.service() == removed.service() && await.topic().equals(removed.topic()));
        }
        boolean anotherHoldOnService = result.stream().anyMatch(step -> step instanceof Step.Arm arm
                && arm.service() == removed.service() && Fault.HOLD.equals(arm.fault().type()));
        if (!anotherHoldOnService) {
            Service service = removed.service();
            result.removeIf(step -> step instanceof Step.ReleaseAll release && release.service() == service);
        }
        return result;
    }

    /** A simpler version of the same fault, or {@code null} if it is already as simple as it gets. */
    static Fault simplify(Fault fault) {
        return switch (fault.type()) {
            case Fault.DUPLICATE -> fault.copies() > 2 ? Fault.duplicate(2) : null;
            case Fault.DUPLICATE_CONCURRENT -> fault.copies() > 2
                    ? Fault.duplicateConcurrent(2) : Fault.duplicate(fault.copies());
            case Fault.DELAY -> fault.delayMs() > 100 ? Fault.delay(Math.max(100, (fault.delayMs() / 2) / 100 * 100)) : null;
            default -> null;
        };
    }
}
