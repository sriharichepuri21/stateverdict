package dev.stateverdict.core.verdict;

/** Did a business rule hold, according to the collected evidence? */
public enum EvaluationStatus {
    /** The evidence is sufficient and shows the rule holds. */
    SATISFIED,
    /** The evidence is sufficient and shows the rule is broken. */
    VIOLATED,
    /** The evidence is missing or insufficient to decide. Never treated as success. */
    UNKNOWN
}
