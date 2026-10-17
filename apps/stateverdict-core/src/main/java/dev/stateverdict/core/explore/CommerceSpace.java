package dev.stateverdict.core.explore;

import dev.stateverdict.core.schedule.Schedule;
import dev.stateverdict.core.schedule.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The schedule space of the commerce example (order and payment services in this repository). */
public final class CommerceSpace implements ScheduleSpace<Schedule> {

    @Override
    public Generator<Schedule> generator(long seed) {
        return new ScheduleGenerator(seed)::next;
    }

    @Override
    public List<String> problems(Schedule schedule) {
        return schedule.problems();
    }

    @Override
    public String name(Schedule schedule) {
        return schedule.name();
    }

    @Override
    public Schedule renamed(Schedule schedule, String name) {
        return schedule.withSteps(name, schedule.steps());
    }

    @Override
    public List<Candidate<Schedule>> reductions(Schedule schedule) {
        return Reductions.of(schedule).stream()
                .map(candidate -> new Candidate<>(candidate.schedule(), candidate.change()))
                .toList();
    }

    @Override
    public int interventions(Schedule schedule) {
        return Reductions.interventions(schedule);
    }

    @Override
    public String summary(Schedule schedule) {
        List<String> parts = new ArrayList<>();
        for (Step.Arm arm : schedule.arms()) {
            parts.add(arm.topic() + ":" + arm.fault());
        }
        if (parts.isEmpty()) {
            parts.add("no faults");
        }
        parts.add(cancelMode(schedule));
        return String.join(", ", parts);
    }

    @Override
    public List<String> steps(Schedule schedule) {
        return schedule.steps().stream().map(Step::describe).toList();
    }

    @Override
    public void recordCoverage(Schedule schedule, Map<String, Integer> coverage) {
        for (Step.Arm arm : schedule.arms()) {
            coverage.merge(arm.topic() + " " + arm.fault().type(), 1, Integer::sum);
        }
        for (String topic : List.of("payment.requested", "payment.completed")) {
            boolean armed = schedule.arms().stream().anyMatch(arm -> arm.topic().equals(topic));
            if (!armed) {
                coverage.merge(topic + " none", 1, Integer::sum);
            }
        }
        coverage.merge("customer: " + cancelMode(schedule), 1, Integer::sum);
        boolean declined = schedule.steps().stream().anyMatch(step ->
                step instanceof Step.PlaceOrder place && place.amountCents() == ScheduleGenerator.DECLINED_AMOUNT);
        coverage.merge(declined ? "amount: declined" : "amount: approved", 1, Integer::sum);
    }

    static String cancelMode(Schedule schedule) {
        List<Step> steps = schedule.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Step.Cancel) {
                Step previous = i > 0 ? steps.get(i - 1) : null;
                if (previous instanceof Step.AwaitHeld await) {
                    return "cancel while " + await.topic() + " held";
                }
                if (previous instanceof Step.AwaitOrderStatus await) {
                    return "cancel after order " + await.status();
                }
                return "cancel right after order";
            }
        }
        return "no cancel";
    }
}
