package dev.stateverdict.examples.order;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts a checkout: stores a pending order, then publishes {@link PaymentRequested}.
 *
 * <p><b>Known limitation (deliberate for the spike):</b> the database insert and the Kafka publish
 * are two separate operations, not one atomic step. If the process crashes between them, an order
 * can exist without its event. This "dual write" gap is a real class of bug; it is left visible on
 * purpose so StateVerdict can later detect it, and a transactional outbox is the documented fix.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** Kafka header carrying the caller's correlation id, documented in docs/contracts. */
    public static final String CORRELATION_HEADER = "correlation-id";

    private final OrderRepository orders;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Clock clock;

    public CheckoutService(OrderRepository orders, KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.orders = orders;
        this.kafka = kafka;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    /**
     * @param correlationId optional id supplied by the caller (for StateVerdict, the run id); it is
     *                      copied onto the published event as the {@value #CORRELATION_HEADER} header
     */
    public Order checkout(long amountCents, String correlationId) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        Order order = orders.insertPending(UUID.randomUUID(), amountCents);

        PaymentRequested event =
                new PaymentRequested(UUID.randomUUID(), order.id(), order.amountCents(), Instant.now(clock));
        publish(event, correlationId);

        log.info("checkout started orderId={} eventId={} amountCents={} correlationId={}",
                order.id(), event.eventId(), event.amountCents(), correlationId);
        return order;
    }

    private void publish(PaymentRequested event, String correlationId) {
        // Keyed by order id: all events for one order land on the same partition, in order.
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopicsConfig.PAYMENT_REQUESTED, event.orderId().toString(), json.writeValueAsString(event));
        if (correlationId != null) {
            record.headers().add(CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(event, e);
        } catch (Exception e) {
            throw new EventPublishException(event, e);
        }
    }

    /** The order was stored but its payment request could not be published. */
    public static class EventPublishException extends RuntimeException {
        EventPublishException(PaymentRequested event, Throwable cause) {
            super("could not publish payment.requested for order " + event.orderId(), cause);
        }
    }
}
