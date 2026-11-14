package ru.ddia.strategy;

import java.time.Duration;
import java.util.Objects;

/** Immutable, capped backoff without jitter. */
public record ExponentialBackoff(Duration initialDelay, double multiplier, Duration maxDelay)
        implements RetryDelayPolicy {
    public ExponentialBackoff {
        validateDelay(initialDelay, "initialDelay");
        validateDelay(maxDelay, "maxDelay");
        if (!Double.isFinite(multiplier) || multiplier <= 1) {
            throw new IllegalArgumentException("multiplier must be finite and greater than one");
        }
    }

    private static void validateDelay(Duration delay, String name) {
        Objects.requireNonNull(delay, name);
        if (delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            delay.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must fit in signed nanoseconds", exception);
        }
    }

    @Override
    public Duration delayAfter(int consecutiveSlowErrors) {
        if (consecutiveSlowErrors < 0) {
            throw new IllegalArgumentException("Error count must not be negative");
        }
        if (consecutiveSlowErrors == 0) {
            return Duration.ZERO;
        }
        long cap = maxDelay.toNanos();
        double delay = initialDelay.toNanos() * Math.pow(multiplier, consecutiveSlowErrors - 1);
        // Infinity and values beyond long range hit the cap before the cast.
        return Duration.ofNanos(delay >= cap ? cap : (long) delay);
    }
}
