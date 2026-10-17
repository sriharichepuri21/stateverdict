package dev.stateverdict.examples.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers for order-service tests that drive orders over HTTP and payment outcomes over Kafka. */
abstract class OrderFlowTestSupport {

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
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    Order placeOrder() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amountCents\": 129900}")));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readValue(response.body(), Order.class);
    }

    HttpResponse<String> cancel(UUID orderId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/orders/" + orderId + "/cancel")).POST(HttpRequest.BodyPublishers.noBody()));
    }

    Order order(UUID orderId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/orders/" + orderId)).GET());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), Order.class);
    }

    void publishOutcome(UUID orderId, String outcome) throws Exception {
        PaymentCompleted event = new PaymentCompleted(UUID.randomUUID(), orderId, UUID.randomUUID(), outcome, Instant.now());
        kafkaTemplate.send("payment.completed", orderId.toString(), json.writeValueAsString(event)).get();
    }

    void awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(order(orderId).status()).isEqualTo(status));
    }

    /**
     * Waits until every payment.completed event published so far has been processed, by publishing
     * one for a fresh order and waiting for it: the topic has a single partition, so it is processed last.
     */
    void awaitAllOutcomesProcessed() throws Exception {
        Order marker = placeOrder();
        publishOutcome(marker.id(), PaymentCompleted.CAPTURED);
        awaitStatus(marker.id(), Order.CONFIRMED);
    }

    /** Reads refund.requested and returns the records for one order, waiting up to {@code wait}. */
    java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> refundRequestsFor(
            UUID orderId, Duration wait) {
        java.util.Map<String, Object> config = java.util.Map.of(
                org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> found = new java.util.ArrayList<>();
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(config,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer())) {
            consumer.subscribe(java.util.List.of("refund.requested"));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(300))) {
                    if (orderId.toString().equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    void publishOutcome(UUID orderId, String outcome, String correlationId) throws Exception {
        PaymentCompleted event = new PaymentCompleted(UUID.randomUUID(), orderId, UUID.randomUUID(), outcome, Instant.now());
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>("payment.completed", orderId.toString(),
                json.writeValueAsString(event));
        record.headers().add("correlation-id", correlationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
