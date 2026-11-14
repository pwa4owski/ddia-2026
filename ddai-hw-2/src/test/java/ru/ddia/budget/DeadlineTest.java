package ru.ddia.budget;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineTest {
    @Test
    void expiresAtExactBoundaryAndDoesNotReturnNegativeRemainingTime() {
        var ticks = new AtomicLong(100);
        var deadline = new Deadline(ticks::get, Duration.ofNanos(10));

        ticks.set(109);
        assertEquals(1, deadline.remainingNanos());
        assertFalse(deadline.isExpired());
        ticks.set(110);
        assertEquals(0, deadline.remainingNanos());
        assertTrue(deadline.isExpired());
        ticks.set(111);
        assertEquals(0, deadline.remainingNanos());
    }

    @Test
    void handlesMonotonicCounterWrap() {
        var ticks = new AtomicLong(Long.MAX_VALUE - 5);
        var deadline = new Deadline(ticks::get, Duration.ofNanos(10));

        ticks.addAndGet(6);
        assertEquals(4, deadline.remainingNanos());
        ticks.addAndGet(4);
        assertTrue(deadline.isExpired());
    }

    @Test
    void acceptsNegativeTickerOrigin() {
        var ticks = new AtomicLong(-100);
        var deadline = new Deadline(ticks::get, Duration.ofNanos(10));

        ticks.addAndGet(5);
        assertEquals(5, deadline.remainingNanos());
    }

    @Test
    void rejectsInvalidOrUnrepresentableTimeout() {
        Ticker ticker = () -> 0;
        assertThrows(IllegalArgumentException.class, () -> new Deadline(ticker, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Deadline(ticker, Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Deadline(ticker, Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
