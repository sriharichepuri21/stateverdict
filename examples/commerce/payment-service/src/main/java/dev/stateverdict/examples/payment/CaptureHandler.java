package dev.stateverdict.examples.payment;

/**
 * Business logic run for each delivered {@code payment.requested} event.
 *
 * <p>Two implementations exist on purpose, selected by {@code payment.capture.handler}:
 * <ul>
 *   <li>{@code naive}: {@link NaiveCaptureHandler}, the defect StateVerdict must detect</li>
 *   <li>{@code idempotent} (default): {@link IdempotentCaptureHandler}, the fix it must accept</li>
 * </ul>
 */
public interface CaptureHandler {

    String PROPERTY = "payment.capture.handler";

    void capture(PaymentRequested event);
}
