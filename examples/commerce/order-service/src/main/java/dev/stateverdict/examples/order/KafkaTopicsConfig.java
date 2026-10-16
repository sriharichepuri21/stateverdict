package dev.stateverdict.examples.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service publishes to. Spring's KafkaAdmin creates them at startup,
 * which also proves the service can reach the broker.
 */
@Configuration
public class KafkaTopicsConfig {

    public static final String PAYMENT_REQUESTED = "payment.requested";

    /**
     * One partition on purpose: the spec limits v1 controlled dispatch to a declared, single-partition
     * topology so delivery order is well defined (see spec section 4, "Concurrency model").
     */
    @Bean
    NewTopic paymentRequestedTopic() {
        return TopicBuilder.name(PAYMENT_REQUESTED).partitions(1).replicas(1).build();
    }
}
