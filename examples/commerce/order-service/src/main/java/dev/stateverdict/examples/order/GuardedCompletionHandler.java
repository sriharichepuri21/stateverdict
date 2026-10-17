package dev.stateverdict.examples.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Applies a payment outcome only to a PENDING order, with a conditional {@code UPDATE ... WHERE
 * status = 'PENDING'}, so the check and the write are one atomic statement. A customer cancellation
 * that arrives first wins.
 *
 * <p><b>Known gap:</b> if a payment is captured for an order that was cancelled meanwhile, the money
 * has been taken and nothing refunds it. This handler only logs it. A refund flow is future work;
 * a rule for it ("a captured payment for a cancelled order is refunded") would currently fail.
 */
@Component
@ConditionalOnProperty(name = CompletionHandler.PROPERTY, havingValue = "guarded", matchIfMissing = true)
public class GuardedCompletionHandler implements CompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(GuardedCompletionHandler.class);

    private final OrderRepository orders;

    public GuardedCompletionHandler(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void apply(PaymentCompleted event) {
        String status = CompletionHandler.targetStatus(event);
        if (orders.setStatusIfPending(event.orderId(), status) == 1) {
            log.info("order {} set to {} from payment outcome {}", event.orderId(), status, event.outcome());
            return;
        }
        log.warn("payment outcome {} ignored for order {}: order is no longer PENDING{}", event.outcome(),
                event.orderId(), PaymentCompleted.CAPTURED.equals(event.outcome()) ? " (refund required)" : "");
    }
}
