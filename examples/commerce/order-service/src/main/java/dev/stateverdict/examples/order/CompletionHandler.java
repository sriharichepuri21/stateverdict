package dev.stateverdict.examples.order;

/**
 * Applies a payment outcome to its order. Selected by {@code order.completion.handler}:
 * <ul>
 *   <li>{@code guarded} (default): {@link GuardedCompletionHandler}, only a PENDING order moves</li>
 *   <li>{@code naive}: {@link NaiveCompletionHandler}, the defect StateVerdict must find</li>
 * </ul>
 */
public interface CompletionHandler {

    String PROPERTY = "order.completion.handler";

    /** @param correlationId the run id of the consumed record, copied to any event this publishes */
    void apply(PaymentCompleted event, String correlationId);

    static String targetStatus(PaymentCompleted event) {
        return PaymentCompleted.CAPTURED.equals(event.outcome()) ? Order.CONFIRMED : Order.CANCELLED;
    }
}
