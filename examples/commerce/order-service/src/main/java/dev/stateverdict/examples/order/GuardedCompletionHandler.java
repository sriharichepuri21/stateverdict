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
 * <p>If the money was captured for an order the customer cancelled meanwhile, it publishes
 * {@code refund.requested}, so the payment service gives the money back (rule SV-004).
 */
@Component
@ConditionalOnProperty(name = CompletionHandler.PROPERTY, havingValue = "guarded", matchIfMissing = true)
public class GuardedCompletionHandler implements CompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(GuardedCompletionHandler.class);

    private final OrderRepository orders;
    private final OrderEventsPublisher events;
    private final Mutations mutations;

    public GuardedCompletionHandler(OrderRepository orders, OrderEventsPublisher events, Mutations mutations) {
        this.orders = orders;
        this.events = events;
        this.mutations = mutations;
    }

    @Override
    public void apply(PaymentCompleted event, String correlationId) {
        String status = CompletionHandler.targetStatus(event);
        if (mutations.active("M05") && PaymentCompleted.DECLINED.equals(event.outcome())) {
            status = Order.CONFIRMED; // M05 (evaluation defect): a declined payment confirms the order
        }
        if (mutations.active("C01") && orders.findById(event.orderId()).isEmpty()) {
            // C01 (evaluation control): an extra read before the conditional update. Harmless refactor.
            log.warn("payment outcome for unknown order {}", event.orderId());
            return;
        }
        if (orders.setStatusIfPending(event.orderId(), status) == 1) {
            log.info("order {} set to {} from payment outcome {}", event.orderId(), status, event.outcome());
            return;
        }
        boolean cancelled = orders.findById(event.orderId()).map(o -> Order.CANCELLED.equals(o.status())).orElse(false);
        if (PaymentCompleted.CAPTURED.equals(event.outcome()) && cancelled) {
            events.refundRequested(event.orderId(), "captured after customer cancellation", correlationId);
            log.info("payment captured for cancelled order {}: refund requested", event.orderId());
            return;
        }
        log.warn("payment outcome {} ignored for order {}: order is no longer PENDING", event.outcome(), event.orderId());
    }
}
