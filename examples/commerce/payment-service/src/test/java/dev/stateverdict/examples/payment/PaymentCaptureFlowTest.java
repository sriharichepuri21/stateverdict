package dev.stateverdict.examples.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The <b>naive</b> capture handler against real Kafka and PostgreSQL.
 *
 * <p>The second test pins down the deliberate defect: a redelivered event is captured twice. The
 * corrected behaviour is covered by {@link IdempotentCaptureFlowTest}.
 */
@Testcontainers
@SpringBootTest(properties = "payment.capture.handler=naive")
class PaymentCaptureFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    /** In the real stack the order service owns this topic; in this isolated test we create it. */
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
    JsonMapper json;

    @Test
    void oneDeliveryProducesExactlyOneCapture() throws Exception {
        PaymentRequested event = newEvent();

        deliver(event);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(captures.findByOrderId(event.orderId())).hasSize(1));
        Capture capture = captures.findByOrderId(event.orderId()).getFirst();
        assertThat(capture.eventId()).isEqualTo(event.eventId());
        assertThat(capture.amountCents()).isEqualTo(event.amountCents());
    }

    @Test
    void naiveHandlerCapturesARedeliveredEventTwice() throws Exception {
        PaymentRequested event = newEvent();

        deliver(event);
        deliver(event);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(captures.findByOrderId(event.orderId())).hasSize(2));
        assertThat(captures.findByOrderId(event.orderId()))
                .extracting(Capture::eventId)
                .containsOnly(event.eventId());
    }

    private PaymentRequested newEvent() {
        return new PaymentRequested(UUID.randomUUID(), UUID.randomUUID(), 129900, Instant.now());
    }

    private void deliver(PaymentRequested event) throws Exception {
        kafkaTemplate.send("payment.requested", event.orderId().toString(), json.writeValueAsString(event)).get();
    }
}
