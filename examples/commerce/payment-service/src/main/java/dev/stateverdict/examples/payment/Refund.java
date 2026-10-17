package dev.stateverdict.examples.payment;

import java.time.Instant;
import java.util.UUID;

/** A committed refund of one capture. No real money is ever moved. */
public record Refund(UUID id, UUID captureId, UUID orderId, long amountCents, Instant refundedAt) {
}
