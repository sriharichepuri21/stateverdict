package dev.stateverdict.examples.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * <b>Deliberately defective.</b> Records a capture for every delivery without checking whether the
 * event was already processed. Kafka delivers at least once, so a redelivered event charges the
 * customer twice. Kept so StateVerdict's rule SV-002 has a real defect to find.
 */
@Component
@ConditionalOnProperty(name = CaptureHandler.PROPERTY, havingValue = "naive")
public class NaiveCaptureHandler implements CaptureHandler {

    private static final Logger log = LoggerFactory.getLogger(NaiveCaptureHandler.class);

    private final CaptureRepository captures;

    public NaiveCaptureHandler(CaptureRepository captures) {
        this.captures = captures;
        log.warn("NAIVE capture handler active: redelivered events are captured again. Demo use only.");
    }

    @Override
    public void capture(PaymentRequested event) {
        Capture capture = captures.insert(event.orderId(), event.eventId(), event.amountCents());
        log.info("payment captured captureId={} orderId={} eventId={}", capture.id(), capture.orderId(), capture.eventId());
    }
}
