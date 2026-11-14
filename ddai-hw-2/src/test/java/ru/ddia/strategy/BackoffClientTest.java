package ru.ddia.strategy;

import ru.ddia.api.FailureReason;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.SubrequestResult;
import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import ru.ddia.budget.Ticker;
import ru.ddia.internal.Sleeper;
import ru.ddia.support.ManualTime;
import ru.ddia.support.ScriptedFakeSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class BackoffClientTest {
    private final Request request = new Request("query");
    private final ManualTime time = new ManualTime();
    private final BackoffClient client = new BackoffClient(time, time);
    private final SubrequestResult.Success success = new SubrequestResult.Success(200, "answer");
    private final SubrequestResult.ServerError failure = new SubrequestResult.ServerError(503);

    private BackoffSettings settings(int attempts, long totalMillis, ExponentialBackoff policy) {
        return new BackoffSettings(new RetryBudget(attempts, 10, 10, 10),
                new LatencyBudget(Duration.ofMillis(totalMillis)), policy);
    }

    private ExponentialBackoff policy() {
        return new ExponentialBackoff(Duration.ofMillis(10), 2, Duration.ofMillis(25));
    }

    @Test
    void delaysGrowAfter5xxAndTimeoutAndAreCapped() throws Exception {
        var starts = new ArrayList<Long>();
        try (var sender = new ScriptedFakeSender("A", reply(failure),
                reply(new SubrequestResult.Timeout()), reply(failure), reply(failure), reply(success))) {
            var result = client.execute(request, settings(5, 1000, policy()), value -> {
                starts.add(time.read());
                return sender.send(value);
            });
            assertEquals(new OperationResult.Success(200, "answer", 5), result);
            assertEquals(List.of(10L, 20L, 25L, 25L), sleepMillis());
            assertEquals(List.of(0L, 10L, 30L, 55L, 80L),
                    starts.stream().map(n -> Duration.ofNanos(n).toMillis()).toList());
            assertEquals(5, sender.calls().size());
        }
    }

    @Test
    void exhaustedAttemptsDoNotSleepAfterLastFailure() throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(failure), reply(failure), reply(success))) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(2, 1000, policy()), sender));
            assertEquals(FailureReason.ATTEMPTS_EXHAUSTED, result.reason());
            assertEquals(List.of(10L), sleepMillis());
            assertEquals(2, sender.calls().size());
        }
    }

    @Test
    void exhaustedCategoryDoesNotSleep() throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(failure), reply(success))) {
            var settings = new BackoffSettings(new RetryBudget(5, 5, 0, 5),
                    new LatencyBudget(Duration.ofSeconds(1)), policy());
            var result = assertInstanceOf(OperationResult.Failure.class, client.execute(request, settings, sender));
            assertEquals(FailureReason.FAILURES_EXHAUSTED, result.reason());
            assertTrue(time.sleeps().isEmpty());
            assertEquals(1, sender.calls().size());
        }
    }

    @Test
    void deadlineClipsPauseAndDoesNotSpendAnotherAttempt() throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(failure), reply(success))) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(5, 5, policy()), sender));
            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(failure, result.lastResult().orElseThrow());
            assertEquals(1, result.attempts());
            assertEquals(1, sender.calls().size());
            assertEquals(List.of(5L), sleepMillis());
        }
    }

    @Test
    void interruptionDuringPauseStopsRetries() throws Exception {
        var interruptible = new BackoffClient(time, delay -> {
            throw new InterruptedException("cancelled");
        });
        try (var sender = new ScriptedFakeSender("A", reply(failure), reply(success))) {
            assertThrows(InterruptedException.class,
                    () -> interruptible.execute(request, settings(5, 1000, policy()), sender));
            assertEquals(1, sender.calls().size());
        }
    }

    @Test
    void freshLogicalRequestStartsAtInitialDelay() throws Exception {
        var settings = settings(3, 1000, policy());
        for (int i = 0; i < 2; i++) {
            try (var sender = new ScriptedFakeSender("A", reply(failure), reply(success))) {
                assertEquals(new OperationResult.Success(200, "answer", 2),
                        client.execute(request, settings, sender));
                assertEquals(2, sender.calls().size());
            }
        }
        assertEquals(List.of(10L, 10L), sleepMillis());
    }

    @Test
    void hugeMultiplierSaturatesWithoutOverflowDuringExecution() throws Exception {
        var huge = new ExponentialBackoff(Duration.ofNanos(1), Double.MAX_VALUE, Duration.ofMillis(30));
        try (var sender = new ScriptedFakeSender("A", reply(failure), reply(failure), reply(success))) {
            assertEquals(new OperationResult.Success(200, "answer", 3),
                    client.execute(request, settings(3, 1000, huge), sender));
            assertEquals(List.of(Duration.ofNanos(1), Duration.ofMillis(30)), time.sleeps());
            assertEquals(3, sender.calls().size());
        }
        assertEquals(Duration.ofNanos(Long.MAX_VALUE),
                new ExponentialBackoff(Duration.ofSeconds(1), 2, Duration.ofNanos(Long.MAX_VALUE))
                        .delayAfter(Integer.MAX_VALUE));
    }

    @Test
    void invalidBackoffSettingsAreRejected() {
        Duration positive = Duration.ofMillis(1);
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(Duration.ZERO, 2, positive));
        assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(positive, 2, Duration.ZERO));
        for (double multiplier : new double[]{0, 1, -2, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ExponentialBackoff(positive, multiplier, positive));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(Duration.ofSeconds(Long.MAX_VALUE), 2, positive));
    }

    private List<Long> sleepMillis() {
        return time.sleeps().stream().map(Duration::toMillis).toList();
    }

    @Test
    void realSleepIsBoundedByGlobalDeadline() throws Exception {
        var realClient = new BackoffClient(Ticker.systemTicker(), Sleeper.systemSleeper());
        var longPause = new ExponentialBackoff(Duration.ofSeconds(10), 2, Duration.ofSeconds(20));
        try (var sender = new ScriptedFakeSender("A", reply(failure), reply(success))) {
            long started = System.nanoTime();
            var result = assertInstanceOf(OperationResult.Failure.class,
                    realClient.execute(request, settings(5, 150, longPause), sender));
            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(1, sender.calls().size());
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
        }
    }

    private static ScriptedFakeSender.Step.Reply reply(SubrequestResult outcome) {
        return new ScriptedFakeSender.Step.Reply(outcome);
    }
}
