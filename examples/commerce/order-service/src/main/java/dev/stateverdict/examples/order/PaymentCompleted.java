package dev.stateverdict.examples.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Event consumed from {@code payment.completed}: the order service's own copy of the contract in
 * {@code docs/contracts/payment-completed.md}.
 */
public record PaymentCompleted(UUID eventId, UUID orderId, UUID requestEventId, String outcome, Instant occurredAt) {

    public static final String CAPTURED = "CAPTURED";
    public static final String DECLINED = "DECLINED";
}
