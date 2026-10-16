package dev.stateverdict.dispatch;

import java.time.Instant;
import java.util.UUID;

/**
 * A parked message, as reported to StateVerdict.
 *
 * @param token       pass this to {@link ControlledDispatcher#release} to deliver the message
 * @param autoRelease when a {@link Intervention.Delay} will release it, or {@code null} for a hold
 */
public record HeldMessage(UUID token, String runId, String topic, String eventKey, Instant heldAt, Instant autoRelease) {
}
