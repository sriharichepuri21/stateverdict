package dev.stateverdict.core.rules;

import dev.stateverdict.core.evidence.EvidenceBundle;

/**
 * A versioned business invariant. Implementations must be pure: the same bundle always yields the
 * same result, with no I/O and no clock reads.
 */
public interface Rule {

    String id();

    int version();

    String description();

    RuleResult evaluate(EvidenceBundle evidence);

    /**
     * Whether this is a bounded "eventually" rule rather than a safety rule. A safety rule (nothing bad
     * ever happens, e.g. "at most one capture") is judged on the final evidence. An eventual rule
     * (something good happens in time, e.g. "the refund arrives") may need a little longer: the runner
     * keeps re-observing within a bounded window while it is not yet satisfied. The window only ever
     * extends observation, so safety rules lose nothing.
     */
    default boolean eventual() {
        return false;
    }
}
