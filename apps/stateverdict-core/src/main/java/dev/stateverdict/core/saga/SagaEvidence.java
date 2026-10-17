package dev.stateverdict.core.saga;

import tools.jackson.databind.JsonNode;

/** Evidence ids collected by {@link SagaRunner} and read by the saga rules. */
public final class SagaEvidence {

    /** What the run set up: orderId, customerId, productId, price, count, funds, stock. */
    public static final String SETUP = "saga.setup";
    /** The order as the order service's state store holds it, or {@code {"found": false}}. */
    public static final String ORDER = "saga.order";
    /** The run's customer, from the payment service. */
    public static final String CUSTOMER = "saga.customer";
    /** The run's product, from the stock service. */
    public static final String PRODUCT = "saga.product";
    public static final String PAYMENT_JOURNAL = "dispatch.journal.payment";
    public static final String STOCK_JOURNAL = "dispatch.journal.stock";

    /** The statuses that end the saga. */
    public static boolean decided(String status) {
        return "CONFIRMED".equals(status) || "REJECTED".equals(status) || "ROLLBACK".equals(status);
    }

    static Long number(JsonNode node) {
        return node != null && node.isNumber() ? node.longValue() : null;
    }

    private SagaEvidence() {
    }
}
