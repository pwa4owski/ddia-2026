package ru.ddia.budget;

/** Monotonic nanoseconds; values have no wall-clock meaning. */
@FunctionalInterface
public interface Ticker {
    long read();

    static Ticker systemTicker() {
        return System::nanoTime;
    }
}
