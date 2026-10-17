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
 * Refunds an order's captures when {@code refund.requested} arrives. Goes through the dispatcher like
 * every other consumer, so StateVerdict can delay, hold or duplicate refund requests too.
 *
 * <p>A request for an order with no capture yet refunds nothing. The order service only requests a
 * refund after it has received CAPTURED, which the payment service publishes after the capture commits.
 */
@Component
public class RefundRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestedListener.class);

    private final RefundRepository refunds;
    private final Dispatcher dispatcher;
    private final JsonMapper json;

    public RefundRequestedListener(RefundRepository refunds, Dispatcher dispatcher, JsonMapper json) {
        this.refunds = refunds;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @KafkaListener(topics = KafkaTopicsConfig.REFUND_REQUESTED)
    public void onRecord(ConsumerRecord<String, String> record) throws Exception {
        RefundRequested event = json.readValue(record.value(), RefundRequested.class);
        Header header = record.headers().lastHeader(PaymentRequestedListener.CORRELATION_HEADER);
        String runId = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        dispatcher.dispatch(new InboundMessage<>(KafkaTopicsConfig.REFUND_REQUESTED, runId,
                event.eventId().toString(), event), this::refund);
    }

    void refund(RefundRequested event) {
        int refunded = refunds.refundAllCaptures(event.orderId());
        log.info("refund requested for order {} ({}): {} capture(s) refunded", event.orderId(), event.reason(), refunded);
    }
}
