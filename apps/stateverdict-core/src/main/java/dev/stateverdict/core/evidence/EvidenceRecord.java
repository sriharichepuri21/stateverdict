package dev.stateverdict.core.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;

/**
 * One immutable observation of committed state, with provenance (spec section 7).
 *
 * @param id         stable name rules use to find it, e.g. {@code payment.captures}
 * @param source     which service and endpoint produced it
 * @param observedAt when StateVerdict read it (observation time, not event time)
 * @param sha256     hash of the exact response body, so the evidence can be checked later
 * @param body       the parsed response body
 */
public record EvidenceRecord(String id, String source, Instant observedAt, String sha256, JsonNode body) {

    public static EvidenceRecord of(String id, String source, Instant observedAt, String rawBody, JsonNode body) {
        return new EvidenceRecord(id, source, observedAt, sha256(rawBody), body);
    }

    static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
