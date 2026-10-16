package dev.stateverdict.examples.payment;

import dev.stateverdict.dispatch.Dispatcher;
import dev.stateverdict.dispatch.InboundMessage;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>The business handler is intentionally naive:</b> it does not check whether the event was
 * already processed. Kafka delivers at least once, so a redelivered event produces a second capture.
 * This is the defect StateVerdict's first rule (SV-002, at most one capture per order) is built to
 * detect. The corrected, idempotent handler is added in a later spike step.
 */
@Component
public class PaymentRequestedListener {

    static final String TOPIC = "payment.requested";
    static final String CORRELATION_HEADER = "correlation-id";

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestedListener.class);

    private final CaptureRepository captures;
    private final Dispatcher dispatcher;
    private final JsonMapper json;

    public PaymentRequestedListener(CaptureRepository captures, Dispatcher dispatcher, JsonMapper json) {
        this.captures = captures;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @KafkaListener(topics = TOPIC)
    public void onRecord(ConsumerRecord<String, String> record) throws Exception {
        PaymentRequested event = json.readValue(record.value(), PaymentRequested.class);
        String runId = header(record, CORRELATION_HEADER);
        dispatcher.dispatch(new InboundMessage<>(TOPIC, runId, event.eventId().toString(), event), this::capture);
    }

    /** The business handler. */
    void capture(PaymentRequested event) {
        Capture capture = captures.insert(event.orderId(), event.eventId(), event.amountCents());
        log.info("payment captured captureId={} orderId={} eventId={}",
                capture.id(), capture.orderId(), capture.eventId());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
