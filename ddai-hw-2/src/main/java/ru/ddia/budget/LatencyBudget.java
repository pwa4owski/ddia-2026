package ru.ddia.budget;

import java.time.Duration;
import java.util.Objects;

public record LatencyBudget(Duration totalTimeout, Duration subrequestTimeout) {
    public LatencyBudget {
        validate(totalTimeout, "totalTimeout");
        validate(subrequestTimeout, "subrequestTimeout");
    }

    public LatencyBudget(Duration timeout) {
        this(timeout, timeout);
    }

    private static void validate(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must fit in signed nanoseconds", exception);
        }
    }
}
