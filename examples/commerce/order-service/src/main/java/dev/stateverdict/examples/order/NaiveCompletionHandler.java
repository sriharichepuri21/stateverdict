package dev.stateverdict.examples.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * <b>Deliberately defective.</b> Sets the order status from the payment outcome without checking the
 * current status. If the customer cancels while the payment is still in flight, a late
 * {@code CAPTURED} turns the cancelled order back into CONFIRMED. Every unit test of this handler
 * passes; the bug only exists in a particular interleaving of a cancel request and an event.
 */
@Component
@ConditionalOnProperty(name = CompletionHandler.PROPERTY, havingValue = "naive")
public class NaiveCompletionHandler implements CompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(NaiveCompletionHandler.class);

    private final OrderRepository orders;

    public NaiveCompletionHandler(OrderRepository orders) {
        this.orders = orders;
        log.warn("NAIVE completion handler active: payment outcomes overwrite cancelled orders. Demo use only.");
    }

    @Override
    public void apply(PaymentCompleted event) {
        String status = CompletionHandler.targetStatus(event);
        orders.setStatus(event.orderId(), status);
        log.info("order {} set to {} from payment outcome {}", event.orderId(), status, event.outcome());
    }
}
