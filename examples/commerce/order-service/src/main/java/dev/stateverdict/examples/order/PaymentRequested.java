package dev.stateverdict.examples.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published to {@code payment.requested} when an order needs payment.
 *
 * <p>{@code eventId} identifies this business event. If the same event is delivered twice, both
 * deliveries carry the same {@code eventId}; that is what lets a consumer recognise a duplicate.
 * The contract is documented in {@code docs/contracts/payment-requested.md}.
 */
public record PaymentRequested(UUID eventId, UUID orderId, long amountCents, Instant occurredAt) {
}
