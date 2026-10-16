package dev.stateverdict.examples.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the order service against real PostgreSQL and Kafka containers and checks the basics:
 * the service is healthy, its schema is migrated, and its topic exists on the broker.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void ordersTableIsMigratedAndEmpty() {
        Integer rows = jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single();

        assertThat(rows).isZero();
    }

    @Test
    void paymentRequestedTopicExistsWithOnePartition() {
        var topics = kafkaAdmin.describeTopics(KafkaTopicsConfig.PAYMENT_REQUESTED);

        assertThat(topics.get(KafkaTopicsConfig.PAYMENT_REQUESTED).partitions()).hasSize(1);
    }
}
