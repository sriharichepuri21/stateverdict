package dev.stateverdict.examples.order;

import dev.stateverdict.dispatch.Dispatcher;
import dev.stateverdict.dispatch.InboundMessage;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies {@code payment.completed} outcomes to orders. Like the payment service, every consumed
 * record passes through the configured {@link Dispatcher}, so StateVerdict can hold, delay or
 * duplicate it in test mode.
 */
@Component
public class PaymentCompletedListener {

    private final CompletionHandler handler;
    private final Dispatcher dispatcher;
    private final JsonMapper json;

    public PaymentCompletedListener(CompletionHandler handler, Dispatcher dispatcher, JsonMapper json) {
        this.handler = handler;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @KafkaListener(topics = KafkaTopicsConfig.PAYMENT_COMPLETED)
    public void onRecord(ConsumerRecord<String, String> record) throws Exception {
        PaymentCompleted event = json.readValue(record.value(), PaymentCompleted.class);
        Header header = record.headers().lastHeader(CheckoutService.CORRELATION_HEADER);
        String runId = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        dispatcher.dispatch(new InboundMessage<>(KafkaTopicsConfig.PAYMENT_COMPLETED, runId,
                event.eventId().toString(), event), handler::apply);
    }
}
