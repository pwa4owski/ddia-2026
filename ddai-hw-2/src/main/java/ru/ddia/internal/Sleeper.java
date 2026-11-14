package ru.ddia.internal;

import java.time.Duration;

/** An interruptible wait; test implementations advance the associated ticker. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    static Sleeper systemSleeper() {
        return Thread::sleep;
    }
}
