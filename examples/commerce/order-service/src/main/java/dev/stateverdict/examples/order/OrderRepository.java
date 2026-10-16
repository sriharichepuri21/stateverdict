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
                        RETURNING id, status, amount_cents, created_at, updated_at
                        """)
                .param("id", id)
                .param("status", Order.PENDING)
                .param("amount", amountCents)
                .query(OrderRepository::map)
                .single();
    }

    public Optional<Order> findById(UUID id) {
        return jdbc.sql("SELECT id, status, amount_cents, created_at, updated_at FROM orders WHERE id = :id")
                .param("id", id)
                .query(OrderRepository::map)
                .optional();
    }

    private static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("amount_cents"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
