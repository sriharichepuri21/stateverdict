package dev.stateverdict.core.schedule;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One step of a {@link Schedule}. Steps run in order; each is either a test intervention
 * (arm a fault, wait for a held message, release) or a business action (place an order, cancel it).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Step.Arm.class, name = "ARM"),
        @JsonSubTypes.Type(value = Step.PlaceOrder.class, name = "PLACE_ORDER"),
        @JsonSubTypes.Type(value = Step.AwaitHeld.class, name = "AWAIT_HELD"),
        @JsonSubTypes.Type(value = Step.Cancel.class, name = "CANCEL"),
        @JsonSubTypes.Type(value = Step.ReleaseAll.class, name = "RELEASE_ALL")
})
public sealed interface Step {

    /** Human-readable one-liner, used in reports and shrinker output. */
    String describe();

    /** Arm {@code fault} for the next message on {@code topic} consumed by {@code service}. */
    record Arm(Service service, String topic, Fault fault) implements Step {
        @Override
        public String describe() {
            return "arm " + fault + " on " + topic + " (" + service.name().toLowerCase() + ")";
        }
    }

    /** Business action: place an order, tagged with the run id. Exactly one per schedule. */
    record PlaceOrder(long amountCents) implements Step {
        @Override
        public String describe() {
            return "place order (" + amountCents + " cents)";
        }
    }

    /** Wait until {@code service} holds at least {@code count} messages from {@code topic}. */
    record AwaitHeld(Service service, String topic, int count) implements Step {
        @Override
        public String describe() {
            return "wait until " + count + " " + topic + " held (" + service.name().toLowerCase() + ")";
        }
    }

    /** Business action: the customer asks to cancel the order. 409 (already moved on) is not an error. */
    record Cancel() implements Step {
        @Override
        public String describe() {
            return "customer cancels the order";
        }
    }

    /** Release every message {@code service} holds for this run, oldest first. */
    record ReleaseAll(Service service) implements Step {
        @Override
        public String describe() {
            return "release all held (" + service.name().toLowerCase() + ")";
        }
    }
}
