package ru.ddia.multi;

import ru.ddia.api.Endpoint;
import ru.ddia.api.Endpoints;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class CircuitBreakingClientTest {
    private final ManualTime time = new ManualTime();
    private final CircuitBreakingClient client = create(2, 0.5);
    private final SubrequestResult.Success success = new SubrequestResult.Success(200, "ok");
    private final SubrequestResult.ServerError error = new SubrequestResult.ServerError(503);
    private final Request request = new Request("query");

    @Test
    void fullWindowAndStrictThresholdAndStableIds() throws Exception {
        assertSent(client, error); // Window is not filled.
        assertSent(client, success); // Exactly 50%, stays closed.
        assertSent(client, error); // Still 50%.
        assertSent(client, error); // Now 100%, opens.
        try (var a = new ScriptedFakeSender("A")) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(2), endpoints("A", a)));
            assertEquals(FailureReason.NO_HEALTHY_ENDPOINTS, result.reason());
            assertEquals(0, result.attempts());
            assertTrue(a.calls().isEmpty());
        }
    }

    @Test
    void skipsOpenEndpointAndContinuesRotation() throws Exception {
        execute(client, "A", error);
        execute(client, "A", error);
        try (var a = new ScriptedFakeSender("A");
             var b = new ScriptedFakeSender("B", reply(error), reply(success))) {
            assertEquals(new OperationResult.Success(200, "ok", 2),
                    client.execute(request, settings(3), new Endpoints(List.of(
                            new Endpoint("A", a), new Endpoint("B", b)))));
            assertTrue(a.calls().isEmpty());
            assertEquals(2, b.calls().size());
        }
    }

    @Test
    void cooldownProbeSuccessClosesAndResetsWindow() throws Exception {
        execute(client, "A", error);
        execute(client, "A", error);
        time.advance(Duration.ofSeconds(1));
        assertInstanceOf(OperationResult.Success.class, execute(client, "A", success));
        execute(client, "A", error); // Only one result after reset, remains closed.
        assertInstanceOf(OperationResult.Success.class, execute(client, "A", success));
    }

    @Test
    void fastErrorsAndTimeoutsBothOpenCircuit() throws Exception {
        execute(client, "A", new SubrequestResult.FastError(429));
        execute(client, "A", new SubrequestResult.Timeout());
        assertEquals(FailureReason.NO_HEALTHY_ENDPOINTS,
                assertInstanceOf(OperationResult.Failure.class, execute(client, "A", success)).reason());
    }

    @Test
    void thresholdOneNeverTripsAndZeroTripsOnFirstFailureInFullWindow() throws Exception {
        var never = create(1, 1);
        execute(never, "A", error);
        assertInstanceOf(OperationResult.Success.class, execute(never, "A", success));
        var zero = create(1, 0);
        execute(zero, "A", error);
        assertEquals(FailureReason.NO_HEALTHY_ENDPOINTS,
                assertInstanceOf(OperationResult.Failure.class, execute(zero, "A", success)).reason());
    }

    @Test
    void settingsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> create(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> create(2, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> create(2, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitSettings(2, 0.5, Duration.ZERO));
    }

    @Test
    void onlyOneHalfOpenProbeAndInterruptionReleasesItsPermission() throws Exception {
        execute(client, "A", error);
        execute(client, "A", error);
        time.advance(Duration.ofSeconds(1));
        var pending = new CompletableFuture<SubrequestResult>();
        try (var a = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(pending));
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var probe = worker.submit(() -> {
                assertThrows(InterruptedException.class,
                        () -> client.execute(request, settings(1), endpoints("A", a)));
            });
            assertTrue(a.awaitFirstSend(Duration.ofSeconds(2)));
            assertEquals(FailureReason.NO_HEALTHY_ENDPOINTS,
                    assertInstanceOf(OperationResult.Failure.class, execute(client, "A", success)).reason());
            worker.shutdownNow();
            probe.get(2, TimeUnit.SECONDS);
            assertTrue(a.calls().getFirst().cancelledAt().isPresent());
        }
        assertInstanceOf(OperationResult.Success.class, execute(client, "A", success));
    }

    private CircuitBreakingClient create(int window, double threshold) {
        return new CircuitBreakingClient(time, time, new CircuitSettings(window, threshold, Duration.ofSeconds(1)));
    }

    @Test
    void oldFailuresLeaveSlidingWindow() throws Exception {
        var target = create(4, 0.5);
        for (var outcome : List.of(error, error, success, success, success, success, error, error)) {
            assertSent(target, outcome);
        }
        // Last four are success, success, error, error: equality must still allow this send.
        assertSent(target, error);
        assertBlocked(target);
    }

    @Test
    void cooldownBoundaryIsExactIncludingAfterFailedProbe() throws Exception {
        var target = create(1, 0);
        assertSent(target, error);
        for (int i = 0; i < 2; i++) {
            time.advance(Duration.ofSeconds(1).minusNanos(1));
            assertBlocked(target);
            time.advance(Duration.ofNanos(1));
            assertSent(target, i == 0 ? error : success);
        }
        assertSent(target, success);
    }

    @Test
    void realHangingTransportIsCancelledAndOpensCircuit() throws Exception {
        var target = new CircuitBreakingClient(Ticker.systemTicker(), Sleeper.systemSleeper(),
                new CircuitSettings(1, 0, Duration.ofSeconds(30)));
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang())) {
            var budgets = new RoundRobinSettings(new RetryBudget(2, 5, 5, 5),
                    new LatencyBudget(Duration.ofSeconds(5), Duration.ofMillis(100)));
            var result = assertInstanceOf(OperationResult.Failure.class,
                    target.execute(request, budgets, endpoints("A", sender)));
            assertEquals(FailureReason.NO_HEALTHY_ENDPOINTS, result.reason());
            assertEquals(1, result.attempts());
            assertInstanceOf(SubrequestResult.Timeout.class, result.lastResult().orElseThrow());
            assertEquals(1, sender.calls().size());
            assertTrue(sender.calls().getFirst().cancelledAt().isPresent());
        }
        assertBlocked(target);
    }

    @Test
    void lateClosedGenerationSuccessCannotCloseHalfOpenProbe() throws Exception {
        var target = create(1, 0);
        var old = new CompletableFuture<SubrequestResult>();
        var probe = new CompletableFuture<SubrequestResult>();
        try (var oldSender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(old));
             var probeSender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(probe));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldResult = workers.submit(() -> target.execute(request, settings(1), endpoints("A", oldSender)));
            assertTrue(oldSender.awaitFirstSend(Duration.ofSeconds(2)));
            assertSent(target, error);
            time.advance(Duration.ofSeconds(1));
            var probeResult = workers.submit(() -> target.execute(request, settings(1), endpoints("A", probeSender)));
            assertTrue(probeSender.awaitFirstSend(Duration.ofSeconds(2)));
            old.complete(success);
            assertInstanceOf(OperationResult.Success.class, oldResult.get(2, TimeUnit.SECONDS));
            assertBlocked(target); // Old success must not free or complete the current probe.
            probe.complete(error);
            assertEquals(FailureReason.ATTEMPTS_EXHAUSTED,
                    assertInstanceOf(OperationResult.Failure.class, probeResult.get(2, TimeUnit.SECONDS)).reason());
            assertBlocked(target);
        }
    }

    private void assertSent(CircuitBreakingClient target, SubrequestResult outcome) throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(outcome))) {
            var result = target.execute(request, settings(1), endpoints("A", sender));
            assertEquals(1, sender.calls().size(), "Expected an actual send, not circuit rejection");
            if (outcome instanceof SubrequestResult.Success value) {
                assertEquals(new OperationResult.Success(value.statusCode(), value.payload(), 1), result);
            } else {
                var failure = assertInstanceOf(OperationResult.Failure.class, result);
                assertEquals(FailureReason.ATTEMPTS_EXHAUSTED, failure.reason());
                assertEquals(outcome, failure.lastResult().orElseThrow());
            }
        }
    }

    @Test
    void decimalThresholdDoesNotOpenAtEqualityDueToMultiplicationRounding() throws Exception {
        var target = create(100, 0.58);
        for (int i = 0; i < 42; i++) {
            assertSent(target, success);
        }
        for (int i = 0; i < 58; i++) {
            assertSent(target, error);
        }
        // 58/100 equals the threshold: the next send must still be admitted.
        assertSent(target, error);
        assertBlocked(target); // Now 59/100 errors.
    }

    private void assertBlocked(CircuitBreakingClient target) throws Exception {
        try (var sender = new ScriptedFakeSender("A")) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    target.execute(request, settings(1), endpoints("A", sender)));
            assertEquals(FailureReason.NO_HEALTHY_ENDPOINTS, result.reason());
            assertEquals(0, result.attempts());
            assertTrue(sender.calls().isEmpty());
        }
    }

    private OperationResult execute(CircuitBreakingClient target, String id, SubrequestResult result) throws Exception {
        try (var sender = new ScriptedFakeSender(id, reply(result))) {
            return target.execute(request, settings(1), endpoints(id, sender));
        }
    }

    private RoundRobinSettings settings(int attempts) {
        return new RoundRobinSettings(new RetryBudget(attempts, 5, 5, 5), new LatencyBudget(Duration.ofSeconds(5)));
    }

    private Endpoints endpoints(String id, ScriptedFakeSender sender) {
        return new Endpoints(List.of(new Endpoint(id, sender)));
    }

    private ScriptedFakeSender.Step.Reply reply(SubrequestResult result) {
        return new ScriptedFakeSender.Step.Reply(result);
    }
}
