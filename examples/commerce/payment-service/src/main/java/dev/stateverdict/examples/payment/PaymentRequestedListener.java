package dev.stateverdict.examples.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures payment for each {@code payment.requested} event.
 *
 * <p><b>This handler is intentionally naive:</b> it does not check whether the event was already
 * processed. Kafka delivers at least once, so a redelivered event produces a second capture. This is
 * the defect StateVerdict's first rule (SV-002, at most one capture per order) is built to detect.
 * The corrected, idempotent handler is added in a later spike step.
 */
@Component
public class PaymentRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestedListener.class);

    private final CaptureRepository captures;
    private final JsonMapper json;

    public PaymentRequestedListener(CaptureRepository captures, JsonMapper json) {
        this.captures = captures;
        this.json = json;
    }

    @KafkaListener(topics = "payment.requested")
    public void onPaymentRequested(String payload) {
        PaymentRequested event = json.readValue(payload, PaymentRequested.class);
        Capture capture = captures.insert(event.orderId(), event.eventId(), event.amountCents());
        log.info("payment captured captureId={} orderId={} eventId={}",
                capture.id(), capture.orderId(), capture.eventId());
    }
}
