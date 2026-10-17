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
    private final PaymentDecision decision;
    private final PaymentEventsPublisher events;

    public NaiveCaptureHandler(CaptureRepository captures, PaymentDecision decision, PaymentEventsPublisher events) {
        this.captures = captures;
        this.decision = decision;
        this.events = events;
        log.warn("NAIVE capture handler active: redelivered events are captured again. Demo use only.");
    }

    @Override
    public void capture(PaymentRequested event, String correlationId) {
        String outcome = decision.decide(event.amountCents());
        if (PaymentCompleted.CAPTURED.equals(outcome)) {
            Capture capture = captures.insert(event.orderId(), event.eventId(), event.amountCents());
            log.info("payment captured captureId={} orderId={} eventId={}",
                    capture.id(), capture.orderId(), capture.eventId());
        } else {
            log.info("payment declined orderId={} eventId={}", event.orderId(), event.eventId());
        }
        events.completed(event, outcome, correlationId);
    }
}
