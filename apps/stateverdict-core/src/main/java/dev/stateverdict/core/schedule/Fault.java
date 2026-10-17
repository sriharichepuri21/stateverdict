package dev.stateverdict.core.schedule;

/**
 * A fault for the next matching message, in the same shape as the services' control API.
 *
 * @param type    {@code HOLD}, {@code DUPLICATE}, {@code DUPLICATE_CONCURRENT} or {@code DELAY}
 * @param copies  for {@code DUPLICATE} (copies one after another) and {@code DUPLICATE_CONCURRENT}
 *                (copies at the same moment, on parallel threads), at least 2
 * @param delayMs for {@code DELAY}, positive
 */
public record Fault(String type, Integer copies, Long delayMs) {

    public static final String HOLD = "HOLD";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String DUPLICATE_CONCURRENT = "DUPLICATE_CONCURRENT";
    public static final String DELAY = "DELAY";

    public static Fault hold() {
        return new Fault(HOLD, null, null);
    }

    public static Fault duplicate(int copies) {
        return new Fault(DUPLICATE, copies, null);
    }

    public static Fault duplicateConcurrent(int copies) {
        return new Fault(DUPLICATE_CONCURRENT, copies, null);
    }

    public static Fault delay(long delayMs) {
        return new Fault(DELAY, null, delayMs);
    }

    /** Problems with this fault, empty if valid. */
    java.util.List<String> problems() {
        if (type == null) {
            return java.util.List.of("fault type is required");
        }
        return switch (type) {
            case HOLD -> java.util.List.of();
            case DUPLICATE -> copies == null || copies < 2
                    ? java.util.List.of("DUPLICATE needs copies >= 2") : java.util.List.of();
            case DUPLICATE_CONCURRENT -> copies == null || copies < 2
                    ? java.util.List.of("DUPLICATE_CONCURRENT needs copies >= 2") : java.util.List.of();
            case DELAY -> delayMs == null || delayMs <= 0
                    ? java.util.List.of("DELAY needs delayMs > 0") : java.util.List.of();
            default -> java.util.List.of("unknown fault type '" + type + "'");
        };
    }

    @Override
    public String toString() {
        return switch (type == null ? "" : type) {
            case DUPLICATE -> "DUPLICATE x" + copies;
            case DUPLICATE_CONCURRENT -> "DUPLICATE_CONCURRENT x" + copies;
            case DELAY -> "DELAY " + delayMs + " ms";
            default -> String.valueOf(type);
        };
    }
}
