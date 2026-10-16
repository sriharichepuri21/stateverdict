package dev.stateverdict.examples.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The default, idempotent capture handler against real Kafka and PostgreSQL. */
@Testcontainers
@SpringBootTest
class IdempotentCaptureFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic paymentRequested() {
            return TopicBuilder.name("payment.requested").partitions(1).replicas(1).build();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CaptureRepository captures;

    @Autowired
    CaptureHandler handler;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Test
    void redeliveredEventIsCapturedOnlyOnce() throws Exception {
        PaymentRequested event = newEvent(UUID.randomUUID());

        deliver(event);
        deliver(event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(processedCount(event.eventId())).isEqualTo(1));
        // Give the second delivery time to be processed too, then confirm it did not capture again.
        PaymentRequested marker = newEvent(UUID.randomUUID());
        deliver(marker);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(captures.findByOrderId(marker.orderId())).hasSize(1));
        assertThat(captures.findByOrderId(event.orderId())).hasSize(1);
    }

    @Test
    void concurrentCopiesOfTheSameEventCommitExactlyOneCapture() throws Exception {
        PaymentRequested event = newEvent(UUID.randomUUID());
        int copies = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    handler.capture(event);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(captures.findByOrderId(event.orderId())).hasSize(1);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void twoDifferentEventsForTheSameOrderAreBothCaptured() {
        // Documents the handler's scope: it deduplicates redeliveries, not distinct events.
        // Two captures for one order here would still be reported by rule SV-002.
        UUID orderId = UUID.randomUUID();

        handler.capture(newEvent(orderId));
        handler.capture(newEvent(orderId));

        assertThat(captures.findByOrderId(orderId)).hasSize(2);
    }

    private int processedCount(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single();
    }

    private static PaymentRequested newEvent(UUID orderId) {
        return new PaymentRequested(UUID.randomUUID(), orderId, 129900, Instant.now());
    }

    private void deliver(PaymentRequested event) throws Exception {
        kafkaTemplate.send("payment.requested", event.orderId().toString(), json.writeValueAsString(event)).get();
    }
}
