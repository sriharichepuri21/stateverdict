package dev.stateverdict.dispatch;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded dispatcher action. The journal is StateVerdict's evidence of what was actually
 * delivered, independent of what the business handler did with it.
 *
 * @param entryId  unique id of this entry; for deliveries this is the delivery attempt id, for
 *                 {@code HELD} entries it equals the token that releases the message
 * @param attempt  1-based delivery count for this {@code eventKey} within the run; {@code null}
 *                 for entries that are not deliveries
 * @param outcome  whether the handler completed; {@code null} for entries that are not deliveries
 * @param error    the handler's failure message when {@code outcome} is {@code FAILED}
 */
public record JournalEntry(
        UUID entryId,
        String runId,
        String topic,
        String eventKey,
        Action action,
        Integer attempt,
        Outcome outcome,
        String error,
        Instant at) {

    public enum Action {
        /** The message was parked instead of delivered. */
        HELD,
        /** The handler was invoked. */
        DELIVERED
    }

    public enum Outcome {
        /** The handler returned normally. */
        ACKNOWLEDGED,
        /** The handler threw. */
        FAILED
    }
}
