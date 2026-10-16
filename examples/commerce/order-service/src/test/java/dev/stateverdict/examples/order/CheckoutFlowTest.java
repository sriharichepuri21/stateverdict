package dev.stateverdict.examples.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Placing an order stores it as PENDING and publishes exactly one payment.requested event for it. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void placingAnOrderStoresItPendingAndPublishesOnePaymentRequest() throws Exception {
        HttpResponse<String> created = post("/orders", "{\"amountCents\": 129900}");

        assertThat(created.statusCode()).isEqualTo(201);
        Order order = json.readValue(created.body(), Order.class);
        assertThat(order.status()).isEqualTo(Order.PENDING);
        assertThat(order.amountCents()).isEqualTo(129900);

        HttpResponse<String> fetched = get("/orders/" + order.id());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(json.readValue(fetched.body(), Order.class).status()).isEqualTo(Order.PENDING);

        List<PaymentRequested> events = paymentRequestsFor(order.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().amountCents()).isEqualTo(129900);
        assertThat(events.getFirst().eventId()).isNotNull();
    }

    @Test
    void nonPositiveAmountIsRejected() throws Exception {
        assertThat(post("/orders", "{\"amountCents\": 0}").statusCode()).isEqualTo(400);
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        assertThat(get("/orders/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    /** Reads the whole topic and returns the events for one order. */
    private List<PaymentRequested> paymentRequestsFor(UUID orderId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<PaymentRequested> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopicsConfig.PAYMENT_REQUESTED));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    PaymentRequested event = json.readValue(record.value(), PaymentRequested.class);
                    if (event.orderId().equals(orderId)) {
                        assertThat(record.key()).isEqualTo(orderId.toString());
                        found.add(event);
                    }
                }
            }
            // Keep reading briefly to make sure no second event follows.
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                PaymentRequested event = json.readValue(record.value(), PaymentRequested.class);
                if (event.orderId().equals(orderId)) {
                    found.add(event);
                }
            }
        }
        return found;
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
