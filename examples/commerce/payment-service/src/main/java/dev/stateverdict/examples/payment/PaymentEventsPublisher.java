package dev.stateverdict.examples.payment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code payment.completed}, copying the run's correlation id so StateVerdict can follow
 * the workflow into the order service.
 *
 * <p><b>Known limitation:</b> the idempotent handler calls this after its transaction commits, so an
 * event never announces an uncommitted capture. If the publish then fails, the capture exists without
 * its event (a dual write). A transactional outbox is the documented fix and is out of scope for now.
 */
@Component
public class PaymentEventsPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public PaymentEventsPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public PaymentCompleted completed(PaymentRequested request, String outcome, String correlationId) {
        PaymentCompleted event =
                new PaymentCompleted(UUID.randomUUID(), request.orderId(), request.eventId(), outcome, Instant.now());
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopicsConfig.PAYMENT_COMPLETED, event.orderId().toString(), json.writeValueAsString(event));
        if (correlationId != null) {
            record.headers().add(PaymentRequestedListener.CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing payment.completed", e);
        } catch (Exception e) {
            throw new IllegalStateException("could not publish payment.completed for order " + event.orderId(), e);
        }
        return event;
    }
}
