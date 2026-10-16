package dev.stateverdict.examples.payment;

import java.time.Instant;
import java.util.UUID;

/** A committed payment capture. No real money is ever moved. */
public record Capture(UUID id, UUID orderId, UUID eventId, long amountCents, Instant capturedAt) {
}
