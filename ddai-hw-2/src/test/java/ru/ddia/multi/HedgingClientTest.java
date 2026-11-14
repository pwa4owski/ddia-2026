package ru.ddia.multi;

import ru.ddia.api.Endpoint;
import ru.ddia.api.Endpoints;
import ru.ddia.api.FailureReason;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.SubrequestResult;
import ru.ddia.budget.Ticker;
import ru.ddia.support.ScriptedFakeSender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class HedgingClientTest {
    private final HedgingClient client = new HedgingClient(Ticker.systemTicker());
    private final Request request = new Request("query");
    private final SubrequestResult.Success success = new SubrequestResult.Success(200, "winner");
    private final SubrequestResult.ServerError error = new SubrequestResult.ServerError(503);

    @Test
    void fastFirstSuccessAvoidsHedges() throws Exception {
        try (var a = new ScriptedFakeSender("A", reply(success)); var b = new ScriptedFakeSender("B")) {
            assertEquals(new OperationResult.Success(200, "winner", 1),
                    client.execute(request, settings(5000, 1000), endpoints(a, b)));
            assertTrue(b.calls().isEmpty());
        }
    }

    @Test
    void launchesAllRemainingConcurrentlyAndFirstSuccessWinsAfterAnotherError() throws Exception {
        var first = new CompletableFuture<SubrequestResult>();
        var second = new CompletableFuture<SubrequestResult>();
        var third = new CompletableFuture<SubrequestResult>();
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(first));
             var b = new ScriptedFakeSender("B", new ScriptedFakeSender.Step.Controlled(second));
             var c = new ScriptedFakeSender("C", new ScriptedFakeSender.Step.Controlled(third));
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> client.execute(request, settings(5000, 20), endpoints(a, b, c)));
            assertTrue(b.awaitFirstSend(Duration.ofSeconds(2)));
            assertTrue(c.awaitFirstSend(Duration.ofSeconds(2)));
            assertFalse(first.isDone());
            second.complete(error);
            third.complete(success);
            assertEquals(new OperationResult.Success(200, "winner", 3), result.get(2, TimeUnit.SECONDS));
            assertTrue(a.calls().getFirst().cancelledAt().isPresent());
            first.complete(new SubrequestResult.Success(200, "late"));
            assertEquals(1, a.calls().size());
            assertEquals(1, b.calls().size());
            assertEquals(1, c.calls().size());
        }
    }

    @Test
    void earlyFirstFailureStartsOthersWithoutWaitingForDelay() throws Exception {
        try (var a = new ScriptedFakeSender("A", reply(new SubrequestResult.FastError(429)));
             var b = new ScriptedFakeSender("B", reply(success))) {
            assertEquals(new OperationResult.Success(200, "winner", 2),
                    client.execute(request, settings(1000, 5000), endpoints(a, b)));
        }
    }

    @Test
    void allErrorsWaitUntilDeadline() throws Exception {
        try (var a = new ScriptedFakeSender("A", reply(error));
             var b = new ScriptedFakeSender("B", reply(error))) {
            long started = System.nanoTime();
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(100, 10), endpoints(a, b)));
            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertTrue(System.nanoTime() - started >= Duration.ofMillis(100).toNanos());
            assertEquals(2, result.attempts());
        }
    }

    @Test
    void deadlineCancelsAllHangingCalls() throws Exception {
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang());
             var b = new ScriptedFakeSender("B", new ScriptedFakeSender.Step.Hang())) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(150, 0), endpoints(a, b)));
            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(2, result.attempts());
            assertTrue(a.calls().getFirst().cancelledAt().isPresent());
            assertTrue(b.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void interruptionCancelsOutstandingCall() throws Exception {
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang());
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> {
                assertThrows(InterruptedException.class,
                        () -> client.execute(request, settings(5000, 1000), endpoints(a)));
            });
            assertTrue(a.awaitFirstSend(Duration.ofSeconds(2)));
            worker.shutdownNow();
            result.get(2, TimeUnit.SECONDS);
            assertTrue(a.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> settings(0, 1));
        assertThrows(IllegalArgumentException.class, () -> settings(1, -1));
    }

    @Test
    void globalDeadlineBeforeHedgingDelayNeverStartsOtherEndpoint() throws Exception {
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang());
             var b = new ScriptedFakeSender("B")) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(100, 5000), endpoints(a, b)));
            assertEquals(1, result.attempts());
            assertTrue(b.calls().isEmpty());
            assertTrue(a.calls().getFirst().cancelledAt().isPresent());
        }
    }

    private HedgingSettings settings(long total, long delay) {
        return new HedgingSettings(Duration.ofMillis(total), Duration.ofMillis(delay));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstOfTwoQueuedSuccessesWinsIncludingOriginalEndpoint(boolean originalWins) throws Exception {
        var original = new CompletableFuture<SubrequestResult>();
        var hedge = new CompletableFuture<SubrequestResult>();
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(original));
             var b = new ScriptedFakeSender("B", new ScriptedFakeSender.Step.Controlled(hedge));
             var c = new ScriptedFakeSender("C", new ScriptedFakeSender.Step.Hang())) {
            // Both completions enter the queue before the caller resumes processing it.
            var endpoints = new Endpoints(List.of(new Endpoint("A", a), new Endpoint("B", b),
                    new Endpoint("C", value -> {
                        var call = c.send(value);
                        var winner = originalWins ? original : hedge;
                        var loser = originalWins ? hedge : original;
                        winner.complete(new SubrequestResult.Success(201, "first"));
                        loser.complete(new SubrequestResult.Success(202, "second"));
                        return call;
                    })));
            assertEquals(new OperationResult.Success(201, "first", 3),
                    client.execute(request, settings(5000, 0), endpoints));
            assertEquals(1, a.calls().size());
            assertEquals(1, b.calls().size());
            assertEquals(1, c.calls().size());
            assertTrue(c.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void hedgesStartAtDelayBoundaryWithControlledTime() throws Exception {
        var now = new AtomicLong();
        var beforeBoundaryObserved = new CountDownLatch(1);
        long boundary = Duration.ofMillis(20).toNanos();
        var controlled = new HedgingClient(() -> {
            long value = now.get();
            if (value == boundary - 1) {
                beforeBoundaryObserved.countDown();
            }
            return value;
        });
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang());
             var b = new ScriptedFakeSender("B", reply(success));
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> controlled.execute(request, settings(5000, 20), endpoints(a, b)));
            assertTrue(a.awaitFirstSend(Duration.ofSeconds(2)));
            now.set(boundary - 1);
            assertTrue(beforeBoundaryObserved.await(2, TimeUnit.SECONDS));
            assertTrue(b.calls().isEmpty());
            now.set(boundary);
            assertEquals(new OperationResult.Success(200, "winner", 2), result.get(2, TimeUnit.SECONDS));
            assertEquals(1, b.calls().size());
            assertTrue(a.calls().getFirst().cancelledAt().isPresent());
        }
    }

    private Endpoints endpoints(ScriptedFakeSender... senders) {
        return new Endpoints(java.util.stream.IntStream.range(0, senders.length)
                .mapToObj(i -> new Endpoint("endpoint-" + i, senders[i])).toList());
    }

    private ScriptedFakeSender.Step.Reply reply(SubrequestResult value) {
        return new ScriptedFakeSender.Step.Reply(value);
    }
}
