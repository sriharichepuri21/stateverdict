package dev.stateverdict.examples.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.stateverdict.dispatch.JournalEntry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payment service in controlled dispatch mode, driven through its test-only HTTP API exactly
 * as StateVerdict will drive it: arm an intervention for a run, publish an event carrying that run
 * id, then read the journal and the committed captures.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stateverdict.dispatch.mode=controlled")
class ControlledDispatchFlowTest {

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

    @Value("${local.server.port}")
    int port;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    CaptureRepository captures;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void withoutAnInterventionTheEventIsDeliveredOnceAndJournaled() throws Exception {
        String runId = newRunId();
        PaymentRequested event = newEvent();

        publish(event, runId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(journal(runId)).hasSize(1));
        assertThat(captures.findByOrderId(event.orderId())).hasSize(1);
    }

    @Test
    void duplicateDeliversTheSameEventTwiceAndTheNaiveHandlerCapturesTwice() throws Exception {
        String runId = newRunId();
        PaymentRequested event = newEvent();
        assertThat(post("/_stateverdict/dispatch/runs/" + runId + "/interventions",
                "{\"type\": \"DUPLICATE\", \"copies\": 2}").statusCode()).isEqualTo(202);

        publish(event, runId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(journal(runId)).hasSize(2));
        List<JournalEntry> journal = journal(runId);
        assertThat(journal).extracting(JournalEntry::action).containsOnly(JournalEntry.Action.DELIVERED);
        assertThat(journal).extracting(JournalEntry::eventKey).containsOnly(event.eventId().toString());
        assertThat(journal).extracting(JournalEntry::attempt).containsExactly(1, 2);
        assertThat(journal).extracting(JournalEntry::outcome).containsOnly(JournalEntry.Outcome.ACKNOWLEDGED);
        assertThat(captures.findByOrderId(event.orderId())).hasSize(2);
    }

    @Test
    void holdParksTheEventUntilReleased() throws Exception {
        String runId = newRunId();
        PaymentRequested event = newEvent();
        post("/_stateverdict/dispatch/runs/" + runId + "/interventions", "{\"type\": \"HOLD\"}");

        publish(event, runId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(get("/_stateverdict/dispatch/runs/" + runId + "/held").body()).contains(event.eventId().toString()));
        assertThat(captures.findByOrderId(event.orderId())).isEmpty();

        HttpResponse<String> released = post("/_stateverdict/dispatch/runs/" + runId + "/held/release-all", "");
        assertThat(released.statusCode()).isEqualTo(200);
        assertThat(captures.findByOrderId(event.orderId())).hasSize(1);
        assertThat(journal(runId)).extracting(JournalEntry::action)
                .containsExactly(JournalEntry.Action.HELD, JournalEntry.Action.DELIVERED);
    }

    @Test
    void resetDiscardsHeldEventsWithoutDelivering() throws Exception {
        String runId = newRunId();
        PaymentRequested event = newEvent();
        post("/_stateverdict/dispatch/runs/" + runId + "/interventions", "{\"type\": \"HOLD\"}");
        publish(event, runId);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(journal(runId)).hasSize(1));

        HttpResponse<String> reset = delete("/_stateverdict/dispatch/runs/" + runId);

        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(reset.body()).contains("\"discardedMessages\":1");
        assertThat(captures.findByOrderId(event.orderId())).isEmpty();
        assertThat(journal(runId)).isEmpty();
    }

    @Test
    void invalidInterventionIsRejected() throws Exception {
        assertThat(post("/_stateverdict/dispatch/runs/r/interventions", "{\"type\": \"DUPLICATE\", \"copies\": 1}")
                .statusCode()).isEqualTo(400);
        assertThat(post("/_stateverdict/dispatch/runs/r/interventions", "{\"type\": \"EXPLODE\"}")
                .statusCode()).isEqualTo(400);
    }

    private static String newRunId() {
        return "run-" + UUID.randomUUID();
    }

    private static PaymentRequested newEvent() {
        return new PaymentRequested(UUID.randomUUID(), UUID.randomUUID(), 129900, Instant.now());
    }

    private void publish(PaymentRequested event, String runId) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payment.requested", event.orderId().toString(), json.writeValueAsString(event));
        record.headers().add("correlation-id", runId.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
    }

    private List<JournalEntry> journal(String runId) throws Exception {
        HttpResponse<String> response = get("/_stateverdict/dispatch/runs/" + runId + "/journal");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), new TypeReference<List<JournalEntry>>() { });
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
