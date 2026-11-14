package ru.ddia.multi;

import ru.ddia.budget.LatencyBudget;
import java.time.Duration;
import java.util.Objects;

/** Hedging has only a global latency budget. */
public record HedgingSettings(Duration totalTimeout, Duration hedgingDelay) {
    public HedgingSettings {
        new LatencyBudget(totalTimeout);
        Objects.requireNonNull(hedgingDelay, "hedgingDelay");
        if (hedgingDelay.isNegative()) {
            throw new IllegalArgumentException("hedgingDelay must not be negative");
        }
        try {
            hedgingDelay.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("hedgingDelay must fit in nanoseconds", exception);
        }
    }
}
