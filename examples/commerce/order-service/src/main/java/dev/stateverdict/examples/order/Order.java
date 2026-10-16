package dev.stateverdict.examples.order;

import java.time.Instant;
import java.util.UUID;

/** An order as stored by the order service. */
public record Order(UUID id, String status, long amountCents, Instant createdAt, Instant updatedAt) {

    public static final String PENDING = "PENDING";
}
