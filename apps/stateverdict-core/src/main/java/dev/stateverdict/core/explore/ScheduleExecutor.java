package dev.stateverdict.core.explore;

import dev.stateverdict.core.report.RunReport;

/** Runs one schedule and returns its report, e.g. {@code ScheduleRunner::run} or {@code SagaRunner::run}. */
@FunctionalInterface
public interface ScheduleExecutor<S> {

    RunReport execute(S schedule) throws InterruptedException;
}
