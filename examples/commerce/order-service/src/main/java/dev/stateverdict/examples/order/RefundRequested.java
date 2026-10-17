package dev.stateverdict.examples.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published to {@code refund.requested} when money was captured for an order the customer had
 * already cancelled. Contract: {@code docs/contracts/refund-requested.md}.
 */
public record RefundRequested(UUID eventId, UUID orderId, String reason, Instant occurredAt) {
}
