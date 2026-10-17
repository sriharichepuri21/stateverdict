package dev.stateverdict.examples.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL access to the {@code refunds} table. */
@Repository
public class RefundRepository {

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refunds every committed capture of the order that has not been refunded yet, in one statement.
     * Idempotent: the unique capture id means a repeated request refunds nothing more.
     *
     * @return how many captures were refunded by this call
     */
    public int refundAllCaptures(UUID orderId) {
        return jdbc.sql("""
                        INSERT INTO refunds (id, capture_id, order_id, amount_cents)
                        SELECT gen_random_uuid(), c.id, c.order_id, c.amount_cents
                        FROM payment_captures c
                        WHERE c.order_id = :orderId
                        ON CONFLICT (capture_id) DO NOTHING
                        """)
                .param("orderId", orderId)
                .update();
    }

    public List<Refund> findByOrderId(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, capture_id, order_id, amount_cents, refunded_at
                        FROM refunds WHERE order_id = :orderId ORDER BY refunded_at, id
                        """)
                .param("orderId", orderId)
                .query(RefundRepository::map)
                .list();
    }

    private static Refund map(ResultSet rs, int rowNum) throws SQLException {
        return new Refund(
                rs.getObject("id", UUID.class),
                rs.getObject("capture_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getLong("amount_cents"),
                rs.getObject("refunded_at", OffsetDateTime.class).toInstant());
    }
}
