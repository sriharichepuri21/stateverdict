package dev.stateverdict.core.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of steps run against the system under test in one run.
 *
 * <p>{@link #problems()} enforces the dependencies that make a schedule meaningful and completable:
 * <ul>
 *   <li>exactly one {@code PLACE_ORDER}; every {@code ARM} comes before it (faults must be armed before
 *       the messages they target exist)</li>
 *   <li>{@code CANCEL} at most once, after {@code PLACE_ORDER}</li>
 *   <li>{@code AWAIT_HELD} only for a service/topic with enough {@code HOLD}s armed, after
 *       {@code PLACE_ORDER}</li>
 *   <li>every {@code HOLD} is followed by a {@code RELEASE_ALL} for its service, so the run can finish</li>
 * </ul>
 * These are the constraints the explorer generates within and the shrinker must preserve.
 */
public record Schedule(String name, List<Step> steps) {

    public Schedule {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(steps);
    }

    public Schedule withSteps(String newName, List<Step> newSteps) {
        return new Schedule(newName, newSteps);
    }

    /** Problems that make this schedule invalid; empty if it is valid. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        int placeIndex = -1;
        int cancels = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String at = "step " + (i + 1) + " (" + step.describe() + "): ";
            switch (step) {
                case Step.PlaceOrder place -> {
                    if (placeIndex >= 0) {
                        problems.add(at + "only one PLACE_ORDER is allowed");
                    }
                    placeIndex = i;
                    if (place.amountCents() <= 0) {
                        problems.add(at + "amountCents must be positive");
                    }
                }
                case Step.Arm arm -> {
                    if (placeIndex >= 0) {
                        problems.add(at + "ARM must come before PLACE_ORDER");
                    }
                    if (arm.service() == null || arm.topic() == null || arm.fault() == null) {
                        problems.add(at + "ARM needs service, topic and fault");
                    } else {
                        arm.fault().problems().forEach(p -> problems.add(at + p));
                        if (Fault.HOLD.equals(arm.fault().type()) && !releasedLater(arm.service(), i)) {
                            problems.add(at + "a HOLD needs a later RELEASE_ALL for " + arm.service());
                        }
                    }
                }
                case Step.AwaitHeld await -> {
                    if (placeIndex < 0) {
                        problems.add(at + "AWAIT_HELD must come after PLACE_ORDER");
                    }
                    long holds = holdsArmed(await.service(), await.topic(), i);
                    if (await.count() < 1 || await.count() > holds) {
                        problems.add(at + "AWAIT_HELD count must be between 1 and the " + holds
                                + " HOLD(s) armed for that service and topic");
                    }
                }
                case Step.Cancel cancel -> {
                    cancels++;
                    if (placeIndex < 0) {
                        problems.add(at + "CANCEL must come after PLACE_ORDER");
                    }
                    if (cancels > 1) {
                        problems.add(at + "only one CANCEL is allowed");
                    }
                }
                case Step.ReleaseAll release -> {
                    if (placeIndex < 0) {
                        problems.add(at + "RELEASE_ALL must come after PLACE_ORDER");
                    }
                }
            }
        }
        if (placeIndex < 0) {
            problems.add("a schedule needs exactly one PLACE_ORDER");
        }
        return problems;
    }

    public boolean isValid() {
        return problems().isEmpty();
    }

    /** Faults armed by this schedule, in order. */
    public List<Step.Arm> arms() {
        return steps.stream().filter(Step.Arm.class::isInstance).map(Step.Arm.class::cast).toList();
    }

    public boolean cancels() {
        return steps.stream().anyMatch(Step.Cancel.class::isInstance);
    }

    private long holdsArmed(Service service, String topic, int before) {
        return steps.subList(0, before).stream()
                .filter(Step.Arm.class::isInstance).map(Step.Arm.class::cast)
                .filter(arm -> arm.service() == service && Objects.equals(arm.topic(), topic)
                        && arm.fault() != null && Fault.HOLD.equals(arm.fault().type()))
                .count();
    }

    private boolean releasedLater(Service service, int after) {
        return steps.subList(after + 1, steps.size()).stream()
                .anyMatch(step -> step instanceof Step.ReleaseAll release && release.service() == service);
    }
}
