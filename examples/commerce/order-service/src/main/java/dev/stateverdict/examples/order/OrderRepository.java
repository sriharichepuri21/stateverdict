package dev.stateverdict.examples.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL access to the {@code orders} table. */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Order insertPending(UUID id, long amountCents) {
        return jdbc.sql("""
                        INSERT INTO orders (id, status, amount_cents)
                        VALUES (:id, :status, :amount)
                        RETURNING id, status, amount_cents, created_at, updated_at, cancel_requested_at
                        """)
                .param("id", id)
                .param("status", Order.PENDING)
                .param("amount", amountCents)
                .query(OrderRepository::map)
                .single();
    }

    public Optional<Order> findById(UUID id) {
        return jdbc.sql("SELECT id, status, amount_cents, created_at, updated_at, cancel_requested_at FROM orders WHERE id = :id")
                .param("id", id)
                .query(OrderRepository::map)
                .optional();
    }

    /**
     * Customer cancellation: only a PENDING order can be cancelled.
     *
     * @return true if the order was PENDING and is now CANCELLED
     */
    public boolean cancelIfPending(UUID id) {
        return jdbc.sql("""
                        UPDATE orders
                        SET status = 'CANCELLED', cancel_requested_at = now(), updated_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .update() == 1;
    }

    /** Sets the status unconditionally. Used only by the deliberately naive completion handler. */
    public int setStatus(UUID id, String status) {
        return jdbc.sql("UPDATE orders SET status = :status, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status)
                .update();
    }

    /** Moves the order to {@code status} only if it is still PENDING. Returns the number of rows changed. */
    public int setStatusIfPending(UUID id, String status) {
        return jdbc.sql("UPDATE orders SET status = :status, updated_at = now() WHERE id = :id AND status = 'PENDING'")
                .param("id", id)
                .param("status", status)
                .update();
    }

    private static Order map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime cancelRequestedAt = rs.getObject("cancel_requested_at", OffsetDateTime.class);
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("amount_cents"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                cancelRequestedAt == null ? null : cancelRequestedAt.toInstant());
    }
}
