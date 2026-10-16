package dev.stateverdict.examples.payment;

import dev.stateverdict.dispatch.Dispatcher;
import dev.stateverdict.dispatch.InboundMessage;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures payment for each {@code payment.requested} event.
 *
 * <p>Every consumed record goes through the configured {@link Dispatcher} before the business
 * handler runs. In production that is a direct call; in test mode StateVerdict can hold, delay or
 * duplicate the delivery (see {@link DispatchConfig}).
 *
 * <p>The business logic itself is a {@link CaptureHandler}: idempotent by default, or the
 * deliberately naive variant when {@code payment.capture.handler=naive}.
 */
@Component
public class PaymentRequestedListener {

    static final String TOPIC = "payment.requested";
    static final String CORRELATION_HEADER = "correlation-id";

    private final CaptureHandler handler;
    private final Dispatcher dispatcher;
    private final JsonMapper json;

    public PaymentRequestedListener(CaptureHandler handler, Dispatcher dispatcher, JsonMapper json) {
        this.handler = handler;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @KafkaListener(topics = TOPIC)
    public void onRecord(ConsumerRecord<String, String> record) throws Exception {
        PaymentRequested event = json.readValue(record.value(), PaymentRequested.class);
        String runId = header(record, CORRELATION_HEADER);
        dispatcher.dispatch(new InboundMessage<>(TOPIC, runId, event.eventId().toString(), event), handler::capture);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
