package dev.stateverdict.examples.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The default (idempotent) handler publishes exactly one {@code payment.completed} per business event. */
@Testcontainers
@SpringBootTest
class PaymentCompletedPublishingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CaptureRepository captures;

    @Autowired
    JsonMapper json;

    @Test
    void approvedPaymentPublishesCapturedWithTheRunsCorrelationId() throws Exception {
        PaymentRequested request = newRequest(129_900);

        deliver(request, "run-7");

        List<ConsumerRecord<String, String>> records = completedFor(request.orderId(), 1);
        assertThat(records).singleElement().satisfies(record -> {
            PaymentCompleted event = json.readValue(record.value(), PaymentCompleted.class);
            assertThat(event.outcome()).isEqualTo(PaymentCompleted.CAPTURED);
            assertThat(event.requestEventId()).isEqualTo(request.eventId());
            Header header = record.headers().lastHeader("correlation-id");
            assertThat(header).isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("run-7");
        });
        assertThat(captures.findByOrderId(request.orderId())).hasSize(1);
    }

    @Test
    void amountAboveTheLimitIsDeclinedWithoutACapture() throws Exception {
        PaymentRequested request = newRequest(600_000);

        deliver(request, null);

        assertThat(completedFor(request.orderId(), 1)).singleElement().satisfies(record ->
                assertThat(json.readValue(record.value(), PaymentCompleted.class).outcome())
                        .isEqualTo(PaymentCompleted.DECLINED));
        assertThat(captures.findByOrderId(request.orderId())).isEmpty();
    }

    @Test
    void redeliveredRequestPublishesOnlyOneCompletion() throws Exception {
        PaymentRequested request = newRequest(129_900);

        deliver(request, null);
        deliver(request, null);
        // A later event on the same single partition proves both copies have been processed.
        PaymentRequested marker = newRequest(100);
        deliver(marker, null);
        completedFor(marker.orderId(), 1);

        assertThat(completedFor(request.orderId(), 1)).hasSize(1);
    }

    private static PaymentRequested newRequest(long amountCents) {
        return new PaymentRequested(UUID.randomUUID(), UUID.randomUUID(), amountCents, Instant.now());
    }

    private void deliver(PaymentRequested request, String correlationId) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payment.requested", request.orderId().toString(), json.writeValueAsString(request));
        if (correlationId != null) {
            record.headers().add("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).get();
    }

    /** Reads payment.completed until {@code atLeast} records for the order arrive, then one more second. */
    private List<ConsumerRecord<String, String>> completedFor(UUID orderId, int atLeast) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("payment.completed"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found.size() < atLeast && System.nanoTime() < deadline) {
                collect(consumer.poll(Duration.ofMillis(500)), orderId, found);
            }
            collect(consumer.poll(Duration.ofSeconds(1)), orderId, found);
        }
        return found;
    }

    private static void collect(Iterable<ConsumerRecord<String, String>> records, UUID orderId,
                                List<ConsumerRecord<String, String>> into) {
        for (ConsumerRecord<String, String> record : records) {
            if (orderId.toString().equals(record.key())) {
                into.add(record);
            }
        }
    }
}
