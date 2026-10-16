package dev.stateverdict.dispatch;

/**
 * Sits between a message consumer and its business handler and decides when, and how many times,
 * the handler runs.
 *
 * <p>Production code uses {@link DirectDispatcher}, which simply calls the handler. Tests use
 * {@link ControlledDispatcher}, which can hold, delay or duplicate deliveries on instruction. The
 * consumer code is identical in both cases; only the configured implementation changes.
 *
 * <p>This dispatcher acts <em>after</em> a message has been consumed from the broker and
 * <em>before</em> business processing (spec ADR-002). It does not control broker internals,
 * partition assignment or network ordering.
 */
public interface Dispatcher {

    <T> void dispatch(InboundMessage<T> message, MessageHandler<T> handler) throws Exception;
}
