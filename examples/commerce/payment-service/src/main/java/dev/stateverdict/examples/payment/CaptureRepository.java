package dev.stateverdict.examples.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL access to the {@code payment_captures} table. */
@Repository
public class CaptureRepository {

    private final JdbcClient jdbc;

    public CaptureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Capture insert(UUID orderId, UUID eventId, long amountCents) {
        return jdbc.sql("""
                        INSERT INTO payment_captures (id, order_id, event_id, amount_cents)
                        VALUES (:id, :orderId, :eventId, :amount)
                        RETURNING id, order_id, event_id, amount_cents, captured_at
                        """)
                .param("id", UUID.randomUUID())
                .param("orderId", orderId)
                .param("eventId", eventId)
                .param("amount", amountCents)
                .query(CaptureRepository::map)
                .single();
    }

    public List<Capture> findByOrderId(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, order_id, event_id, amount_cents, captured_at
                        FROM payment_captures
                        WHERE order_id = :orderId
                        ORDER BY captured_at, id
                        """)
                .param("orderId", orderId)
                .query(CaptureRepository::map)
                .list();
    }

    private static Capture map(ResultSet rs, int rowNum) throws SQLException {
        return new Capture(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getLong("amount_cents"),
                rs.getObject("captured_at", OffsetDateTime.class).toInstant());
    }
}
