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
}
