package dev.stateverdict.examples.payment;

import java.time.Instant;
import java.util.UUID;

/** Event consumed from {@code refund.requested}: the payment service's copy of the contract. */
public record RefundRequested(UUID eventId, UUID orderId, String reason, Instant occurredAt) {
}
