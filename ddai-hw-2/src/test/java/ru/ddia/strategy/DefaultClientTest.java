package ru.ddia.strategy;

import ru.ddia.api.FailureReason;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.SubrequestResult;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.Ticker;
import ru.ddia.support.ScriptedFakeSender;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DefaultClientTest {
    private final Request request = new Request("question");
    private final DefaultClient client = new DefaultClient(Ticker.systemTicker());
    private final DefaultSettings settings = new DefaultSettings(new LatencyBudget(Duration.ofSeconds(3)));

    @Test
    void preservesPayloadAndStatusWithExactlyOneSend() throws InterruptedException {
        try (var sender = new ScriptedFakeSender("A",
                new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(201, "answer"),
                        Duration.ofMillis(20)))) {
            OperationResult result = client.execute(request, settings, sender);

            assertEquals(new OperationResult.Success(201, "answer", 1), result);
            assertEquals(1, settings.maxAttempts());
            assertEquals(1, sender.calls().size());
            var call = sender.calls().getFirst();
            assertEquals(request, call.request());
            assertEquals("A", call.endpointId());
            assertTrue(call.completedAt().isPresent());
            assertTrue(call.completedAt().getAsLong() - call.startedAt() >= 0);
            assertTrue(call.cancelledAt().isEmpty());
        }
    }

    static Stream<SubrequestResult> unsuccessfulResults() {
        return Stream.of(new SubrequestResult.FastError(429),
                new SubrequestResult.ServerError(503), new SubrequestResult.Timeout());
    }

    @ParameterizedTest
    @MethodSource("unsuccessfulResults")
    void preservesUnsuccessfulOutcomeWithoutRetry(SubrequestResult outcome) throws InterruptedException {
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Reply(outcome),
                new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "unused")))) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings, sender));

            assertEquals(FailureReason.ATTEMPTS_EXHAUSTED, result.reason());
            assertEquals(outcome, result.lastResult().orElseThrow());
            assertEquals(1, result.attempts());
            assertEquals(1, sender.calls().size());
            assertTrue(sender.calls().getFirst().cancelledAt().isEmpty());
        }
    }

    @Test
    void subrequestTimeoutCancelsHangingTransport() throws InterruptedException {
        var timeouts = new DefaultSettings(new LatencyBudget(Duration.ofSeconds(5), Duration.ofMillis(150)));
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang())) {
            long startedAt = System.nanoTime();
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, timeouts, sender));

            assertEquals(FailureReason.ATTEMPTS_EXHAUSTED, result.reason());
            assertInstanceOf(SubrequestResult.Timeout.class, result.lastResult().orElseThrow());
            assertEquals(1, result.attempts());
            assertEquals(1, sender.calls().size());
            assertTrue(sender.calls().getFirst().cancelledAt().isPresent());
            assertTrue(sender.calls().getFirst().completedAt().isEmpty());
            assertTrue(System.nanoTime() - startedAt < Duration.ofSeconds(3).toNanos());
        }
    }

    @Test
    void interruptedCallerCancelsActiveCall() throws InterruptedException {
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang())) {
            var termination = new AtomicReference<Throwable>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    client.execute(request, settings, sender);
                    termination.set(new AssertionError("Interrupted call returned normally"));
                } catch (Throwable exception) {
                    termination.set(exception);
                }
            });
            try {
                assertTrue(sender.awaitFirstSend(Duration.ofSeconds(3)));
                caller.interrupt();
                caller.join(Duration.ofSeconds(3));

                assertFalse(caller.isAlive());
                assertInstanceOf(InterruptedException.class, termination.get());
                assertEquals(1, sender.calls().size());
                assertTrue(sender.calls().getFirst().cancelledAt().isPresent());
            } finally {
                caller.interrupt();
                caller.join(Duration.ofSeconds(3));
            }
        }
    }

    @Test
    void alreadyInterruptedCallerDoesNotSend() throws InterruptedException {
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang())) {
            try {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedException.class, () -> client.execute(request, settings, sender));
            } finally {
                Thread.interrupted();
            }
            assertTrue(sender.calls().isEmpty());
        }
    }

    @Test
    void deadlineReachedBeforeSendDoesNotSpendAnAttempt() throws InterruptedException {
        var ticks = new AtomicLong();
        var immediateDeadline = new DefaultClient(() -> ticks.getAndAdd(10));
        var timeouts = new DefaultSettings(new LatencyBudget(Duration.ofNanos(1)));
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang())) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    immediateDeadline.execute(request, timeouts, sender));

            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(0, result.attempts());
            assertTrue(result.lastResult().isEmpty());
            assertTrue(sender.calls().isEmpty());
        }
    }

    @Test
    void successAtExactDeadlineIsRejectedAndCancelled() throws InterruptedException {
        var ticks = new AtomicLong();
        var timedClient = new DefaultClient(ticks::get);
        var timeouts = new DefaultSettings(new LatencyBudget(Duration.ofNanos(100)));
        try (var fake = new ScriptedFakeSender("A",
                new ScriptedFakeSender.Step.Reply(new SubrequestResult.Success(200, "too late")))) {
            SubrequestSender sender = value -> {
                var call = fake.send(value);
                ticks.set(100);
                return call;
            };
            var result = assertInstanceOf(OperationResult.Failure.class,
                    timedClient.execute(request, timeouts, sender));

            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(1, result.attempts());
            assertInstanceOf(SubrequestResult.Timeout.class, result.lastResult().orElseThrow());
            assertTrue(fake.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void lateSuccessCannotChangeReturnedTimeout() throws InterruptedException {
        var source = new CompletableFuture<SubrequestResult>();
        var timeouts = new DefaultSettings(new LatencyBudget(Duration.ofMillis(150)));
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(source))) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, timeouts, sender));
            assertTrue(source.complete(new SubrequestResult.Success(200, "late")));

            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertInstanceOf(SubrequestResult.Timeout.class, result.lastResult().orElseThrow());
            assertTrue(sender.calls().getFirst().completedAt().isEmpty());
            assertTrue(sender.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void exceptionalTransportCompletionRemainsAnImplementationError() throws InterruptedException {
        var source = new CompletableFuture<SubrequestResult>();
        var cause = new IllegalArgumentException("broken transport");
        source.completeExceptionally(cause);
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(source))) {
            var error = assertThrows(IllegalStateException.class,
                    () -> client.execute(request, settings, sender));

            assertEquals(cause, error.getCause());
            assertEquals(1, sender.calls().size());
            assertTrue(sender.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void invalidArgumentsDoNotSend() throws InterruptedException {
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang())) {
            assertThrows(NullPointerException.class, () -> client.execute(null, settings, sender));
            assertThrows(NullPointerException.class, () -> client.execute(request, null, sender));
            assertThrows(NullPointerException.class, () -> client.execute(request, settings, null));
            assertTrue(sender.calls().isEmpty());
        }
    }
}
