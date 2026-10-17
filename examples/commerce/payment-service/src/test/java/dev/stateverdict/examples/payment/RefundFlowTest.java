package dev.stateverdict.examples.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** refund.requested refunds every capture of the order exactly once, against real Kafka and PostgreSQL. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Value("${local.server.port}")
    int port;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CaptureRepository captures;

    @Autowired
    RefundRepository refunds;

    @Autowired
    JsonMapper json;

    @Test
    void refundRequestRefundsTheCaptureOnceEvenIfRepeated() throws Exception {
        UUID orderId = UUID.randomUUID();
        Capture capture = captures.insert(orderId, UUID.randomUUID(), 129_900);

        requestRefund(orderId);
        requestRefund(orderId);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(refunds.findByOrderId(orderId)).hasSize(1));
        processedEverythingSoFar();

        assertThat(refunds.findByOrderId(orderId)).singleElement().satisfies(refund -> {
            assertThat(refund.captureId()).isEqualTo(capture.id());
            assertThat(refund.amountCents()).isEqualTo(129_900);
        });
        String body = http(URI.create("http://localhost:" + port + "/refunds?orderId=" + orderId));
        assertThat(body).contains("\"count\":1", capture.id().toString());
    }

    @Test
    void everyCaptureOfADoubleChargedOrderIsRefunded() throws Exception {
        UUID orderId = UUID.randomUUID();
        captures.insert(orderId, UUID.randomUUID(), 500);
        captures.insert(orderId, UUID.randomUUID(), 500);

        requestRefund(orderId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(refunds.findByOrderId(orderId)).hasSize(2));
    }

    @Test
    void aRefundRequestWithoutCapturesRefundsNothing() throws Exception {
        UUID orderId = UUID.randomUUID();

        requestRefund(orderId);
        processedEverythingSoFar();

        assertThat(refunds.findByOrderId(orderId)).isEmpty();
    }

    private void requestRefund(UUID orderId) throws Exception {
        RefundRequested event = new RefundRequested(UUID.randomUUID(), orderId, "test", Instant.now());
        kafkaTemplate.send("refund.requested", orderId.toString(), json.writeValueAsString(event)).get();
    }

    /** One partition: once a later marker's refund is visible, every earlier request has been processed. */
    private void processedEverythingSoFar() throws Exception {
        UUID marker = UUID.randomUUID();
        captures.insert(marker, UUID.randomUUID(), 100);
        requestRefund(marker);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(refunds.findByOrderId(marker)).hasSize(1));
    }

    private static String http(URI uri) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
