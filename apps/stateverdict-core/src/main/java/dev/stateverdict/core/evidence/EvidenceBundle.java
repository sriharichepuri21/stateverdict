package dev.stateverdict.core.evidence;

import java.util.List;
import java.util.Optional;

/**
 * Everything collected for one run. Rules are pure functions of a bundle, which is what makes
 * evidence replay deterministic (spec ADR-005).
 */
public record EvidenceBundle(String runId, List<EvidenceRecord> records) {

    public EvidenceBundle {
        records = List.copyOf(records);
    }

    public Optional<EvidenceRecord> find(String id) {
        return records.stream().filter(record -> record.id().equals(id)).findFirst();
    }
}
