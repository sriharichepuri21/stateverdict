package dev.stateverdict.examples.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides each business event at most once, using an inbox table.
 *
 * <p>In one transaction it records the event id in {@code processed_events} with
 * {@code ON CONFLICT DO NOTHING}, and only if that insert took effect does it record the capture.
 * Because both writes commit together, there is no window in which the capture exists but the event
 * is not marked as processed (or the other way round). If two copies of the same event are processed
 * at the same moment, PostgreSQL makes the second insert wait for the first transaction and then
 * skip, so exactly one capture is committed.
 *
 * <p><b>Scope:</b> this deduplicates <em>redeliveries of the same event</em> (same {@code eventId}).
 * It does not stop two <em>different</em> events for the same order from both capturing; that would
 * be an upstream bug, and rule SV-002 would still report it, because it counts captures per order.
 */
@Component
@ConditionalOnProperty(name = CaptureHandler.PROPERTY, havingValue = "idempotent", matchIfMissing = true)
public class IdempotentCaptureHandler implements CaptureHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotentCaptureHandler.class);

    private final CaptureRepository captures;
    private final PaymentDecision decision;
    private final PaymentEventsPublisher events;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public IdempotentCaptureHandler(CaptureRepository captures, PaymentDecision decision, PaymentEventsPublisher events,
                                    JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.captures = captures;
        this.decision = decision;
        this.events = events;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(txManager);
    }

    @Override
    public void capture(PaymentRequested event, String correlationId) {
        transaction.executeWithoutResult(status -> {
            int claimed = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING")
                    .param("eventId", event.eventId())
                    .update();
            if (claimed == 0) {
                log.info("duplicate payment.requested ignored orderId={} eventId={}", event.orderId(), event.eventId());
                return;
            }
            String outcome = decision.decide(event.amountCents());
            if (PaymentCompleted.CAPTURED.equals(outcome)) {
                Capture capture = captures.insert(event.orderId(), event.eventId(), event.amountCents());
                log.info("payment captured captureId={} orderId={} eventId={}",
                        capture.id(), capture.orderId(), capture.eventId());
            } else {
                log.info("payment declined orderId={} eventId={}", event.orderId(), event.eventId());
            }
            // Published once per business event; a skipped duplicate publishes nothing.
            events.completed(event, outcome, correlationId);
        });
    }
}
