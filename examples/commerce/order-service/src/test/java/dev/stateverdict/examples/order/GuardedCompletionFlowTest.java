package dev.stateverdict.examples.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The default (guarded) completion handler and customer cancellation, against real Kafka and PostgreSQL. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuardedCompletionFlowTest extends OrderFlowTestSupport {

    @Test
    void capturedPaymentConfirmsAPendingOrder() throws Exception {
        Order order = placeOrder();

        publishOutcome(order.id(), PaymentCompleted.CAPTURED);

        awaitStatus(order.id(), Order.CONFIRMED);
        assertThat(order(order.id()).cancelRequestedAt()).isNull();
    }

    @Test
    void declinedPaymentCancelsAPendingOrder() throws Exception {
        Order order = placeOrder();

        publishOutcome(order.id(), PaymentCompleted.DECLINED);

        awaitStatus(order.id(), Order.CANCELLED);
    }

    @Test
    void customerCancellationWinsOverALateCapturedPayment() throws Exception {
        Order order = placeOrder();
        assertThat(cancel(order.id()).statusCode()).isEqualTo(200);

        publishOutcome(order.id(), PaymentCompleted.CAPTURED);
        awaitAllOutcomesProcessed();

        Order after = order(order.id());
        assertThat(after.status()).isEqualTo(Order.CANCELLED);
        assertThat(after.cancelRequestedAt()).isNotNull();
    }

    @Test
    void onlyAPendingOrderCanBeCancelled() throws Exception {
        Order confirmed = placeOrder();
        publishOutcome(confirmed.id(), PaymentCompleted.CAPTURED);
        awaitStatus(confirmed.id(), Order.CONFIRMED);

        assertThat(cancel(confirmed.id()).statusCode()).isEqualTo(409);
        assertThat(order(confirmed.id()).cancelRequestedAt()).isNull();

        Order pending = placeOrder();
        assertThat(cancel(pending.id()).statusCode()).isEqualTo(200);
        assertThat(cancel(pending.id()).statusCode()).isEqualTo(409);
        assertThat(cancel(UUID.randomUUID()).statusCode()).isEqualTo(404);
    }
}
