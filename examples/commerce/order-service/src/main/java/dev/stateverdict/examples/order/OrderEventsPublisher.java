package dev.stateverdict.examples.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Publishes events the order service produces after checkout, carrying the run's correlation id. */
@Component
public class OrderEventsPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public OrderEventsPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public RefundRequested refundRequested(UUID orderId, String reason, String correlationId) {
        RefundRequested event = new RefundRequested(UUID.randomUUID(), orderId, reason, Instant.now());
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopicsConfig.REFUND_REQUESTED, orderId.toString(), json.writeValueAsString(event));
        if (correlationId != null) {
            record.headers().add(CheckoutService.CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing refund.requested", e);
        } catch (Exception e) {
            throw new IllegalStateException("could not publish refund.requested for order " + orderId, e);
        }
        return event;
    }
}
