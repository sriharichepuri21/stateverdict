package dev.stateverdict.dispatch;

/** The business logic that processes one delivered message, for example "capture this payment". */
@FunctionalInterface
public interface MessageHandler<T> {

    void handle(T payload) throws Exception;
}
