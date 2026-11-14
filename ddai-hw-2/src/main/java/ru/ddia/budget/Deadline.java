package ru.ddia.budget;

import java.time.Duration;
import java.util.Objects;

/** Uses elapsed-time subtraction so a nanoTime wrap does not overflow an end time. */
public final class Deadline {
    private final Ticker ticker;
    private final long startedAt;
    private final long timeoutNanos;

    public Deadline(Ticker ticker, Duration timeout) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Timeout must fit in signed nanoseconds", exception);
        }
        startedAt = ticker.read();
    }

    public long remainingNanos() {
        long elapsed = ticker.read() - startedAt;
        return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }

    public boolean isExpired() {
        return remainingNanos() == 0;
    }
}
