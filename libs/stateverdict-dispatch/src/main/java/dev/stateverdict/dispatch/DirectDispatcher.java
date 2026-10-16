package dev.stateverdict.dispatch;

/** Production behaviour: deliver every message once, immediately, on the caller's thread. */
public final class DirectDispatcher implements Dispatcher {

    @Override
    public <T> void dispatch(InboundMessage<T> message, MessageHandler<T> handler) throws Exception {
        handler.handle(message.payload());
    }
}
