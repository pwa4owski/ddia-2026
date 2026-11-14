package ru.ddia.support;

import ru.ddia.budget.Ticker;
import ru.ddia.internal.Sleeper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Deterministic time for sequential tests; sleeping records and advances time. */
public final class ManualTime implements Ticker, Sleeper {
    private volatile long nanos;
    private final List<Duration> sleeps = new ArrayList<>();

    @Override
    public long read() {
        return nanos;
    }

    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
        advance(duration);
    }

    public void advance(Duration duration) {
        nanos += duration.toNanos();
    }

    public List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }
}
