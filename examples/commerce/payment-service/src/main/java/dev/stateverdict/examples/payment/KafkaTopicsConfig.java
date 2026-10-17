package dev.stateverdict.examples.payment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics this service consumes and produces. Creating a topic that already exists is a no-op, so
 * both services can declare the topics they depend on without startup-order problems.
 * One partition each: the v1 controlled-dispatch topology (ADR-002).
 */
@Configuration
public class KafkaTopicsConfig {

    public static final String PAYMENT_REQUESTED = "payment.requested";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String REFUND_REQUESTED = "refund.requested";

    @Bean
    NewTopic paymentRequestedTopic() {
        return TopicBuilder.name(PAYMENT_REQUESTED).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(PAYMENT_COMPLETED).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic refundRequestedTopic() {
        return TopicBuilder.name(REFUND_REQUESTED).partitions(1).replicas(1).build();
    }
}
