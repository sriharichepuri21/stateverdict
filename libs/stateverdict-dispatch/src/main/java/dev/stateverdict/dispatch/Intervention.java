package dev.stateverdict.dispatch;

import java.time.Duration;
import java.util.Objects;

/**
 * A fault to apply to the next matching message of a run. Each intervention is used once.
 */
public sealed interface Intervention {

    /** Do not deliver; park the message until it is explicitly released. */
    record Hold() implements Intervention {
    }

    /** Deliver the same message {@code copies} times, one after another. */
    record Duplicate(int copies) implements Intervention {
        public Duplicate {
            if (copies < 2) {
                throw new IllegalArgumentException("a duplicate needs at least 2 copies, got " + copies);
            }
        }
    }

    /** Park the message and release it automatically after {@code delay}. */
    record Delay(Duration delay) implements Intervention {
        public Delay {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative() || delay.isZero()) {
                throw new IllegalArgumentException("delay must be positive, got " + delay);
            }
        }
    }
}
