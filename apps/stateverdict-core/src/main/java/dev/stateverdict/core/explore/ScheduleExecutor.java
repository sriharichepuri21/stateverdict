package dev.stateverdict.core.explore;

import dev.stateverdict.core.report.RunReport;
import dev.stateverdict.core.schedule.Schedule;

/** Runs one schedule and returns its report. In production, {@code ScheduleRunner::run}. */
@FunctionalInterface
public interface ScheduleExecutor {

    RunReport execute(Schedule schedule) throws InterruptedException;
}
