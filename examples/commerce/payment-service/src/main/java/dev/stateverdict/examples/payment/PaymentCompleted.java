package dev.stateverdict.examples.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published to {@code payment.completed} once a payment request has been decided.
 * Contract: {@code docs/contracts/payment-completed.md}.
 *
 * @param eventId        identity of this event; a redelivery carries the same id
 * @param orderId        the order that was paid (or not)
 * @param requestEventId the {@code payment.requested} event this answers
 * @param outcome        {@code CAPTURED} or {@code DECLINED}
 */
public record PaymentCompleted(UUID eventId, UUID orderId, UUID requestEventId, String outcome, Instant occurredAt) {

    public static final String CAPTURED = "CAPTURED";
    public static final String DECLINED = "DECLINED";
}
