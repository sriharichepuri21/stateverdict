package dev.stateverdict.examples.order;

import java.time.Instant;
import java.util.UUID;

/**
 * An order as stored by the order service.
 *
 * @param cancelRequestedAt when the customer asked to cancel, or {@code null}; never cleared
 */
public record Order(UUID id, String status, long amountCents, Instant createdAt, Instant updatedAt,
                    Instant cancelRequestedAt) {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
}
