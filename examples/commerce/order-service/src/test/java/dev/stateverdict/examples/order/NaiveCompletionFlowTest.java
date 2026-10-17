package dev.stateverdict.examples.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins down the deliberate defect in the naive completion handler: a payment outcome that arrives
 * after the customer cancelled overwrites the cancellation.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "order.completion.handler=naive")
class NaiveCompletionFlowTest extends OrderFlowTestSupport {

    @Test
    void lateCapturedPaymentConfirmsAnOrderTheCustomerCancelled() throws Exception {
        Order order = placeOrder();
        assertThat(cancel(order.id()).statusCode()).isEqualTo(200);

        publishOutcome(order.id(), PaymentCompleted.CAPTURED);

        awaitStatus(order.id(), Order.CONFIRMED);
        assertThat(order(order.id()).cancelRequestedAt()).isNotNull();
        assertThat(refundRequestsFor(order.id(), java.time.Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void withoutACancellationTheNaiveHandlerBehavesNormally() throws Exception {
        Order order = placeOrder();

        publishOutcome(order.id(), PaymentCompleted.CAPTURED);

        awaitStatus(order.id(), Order.CONFIRMED);
    }
}
