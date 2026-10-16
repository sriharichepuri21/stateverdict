package dev.stateverdict.core.verdict;

/** Did the test infrastructure do its job? Says nothing about whether the business rule held. */
public enum ExecutionStatus {
    /** The scenario ran and every expected delivery was observed within its deadline. */
    COMPLETED,
    /** The scenario ran, but expected deliveries were not all observed before the deadline. */
    TIMED_OUT,
    /** StateVerdict could not drive the scenario (a control call or the trigger failed). */
    INFRA_ERROR
}
