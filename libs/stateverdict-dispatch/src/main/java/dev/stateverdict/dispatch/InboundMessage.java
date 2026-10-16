package dev.stateverdict.dispatch;

import java.util.Objects;

/**
 * A message that a consumer has received and wants delivered to its business handler.
 *
 * @param topic    where the message came from, e.g. {@code payment.requested}
 * @param runId    the StateVerdict run this message belongs to, taken from the message's
 *                 correlation header; {@code null} when the message is not part of any run
 * @param eventKey the business identity of the event (for example its {@code eventId}). Two
 *                 deliveries with the same key are deliveries of the same business event.
 * @param payload  the decoded message
 */
public record InboundMessage<T>(String topic, String runId, String eventKey, T payload) {

    public InboundMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(payload, "payload");
    }
}
