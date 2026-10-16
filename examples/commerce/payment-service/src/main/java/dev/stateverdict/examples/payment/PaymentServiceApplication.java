package dev.stateverdict.examples.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment service of the commerce example (the system under test).
 *
 * <p>Simulates payment capture. It never processes real money. It consumes
 * {@code payment.requested} events published by the order service.
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
