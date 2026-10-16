package dev.stateverdict.examples.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Event consumed from {@code payment.requested}. This is the payment service's own copy of the
 * contract in {@code docs/contracts/payment-requested.md}; the services share no code.
 */
public record PaymentRequested(UUID eventId, UUID orderId, long amountCents, Instant occurredAt) {
}
