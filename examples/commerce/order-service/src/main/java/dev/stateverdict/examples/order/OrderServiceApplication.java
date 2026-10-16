package dev.stateverdict.examples.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order service of the commerce example (the system under test).
 *
 * <p>Owns orders and their status. It publishes {@code payment.requested} events that the
 * payment service consumes.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
