package ru.ddia.strategy;

import java.time.Duration;

/** Stateless policy; the executor owns the consecutive 5xx/timeout count. */
@FunctionalInterface
public interface RetryDelayPolicy {
    /** Zero means the last failure was a 4xx and resets the series. */
    Duration delayAfter(int consecutiveSlowErrors);

    static RetryDelayPolicy immediate() {
        return _ -> Duration.ZERO;
    }
}
