package ru.ddia.strategy;

import ru.ddia.api.FailureReason;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.SubrequestResult;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import ru.ddia.budget.Ticker;
import ru.ddia.support.ManualTime;
import ru.ddia.support.ScriptedFakeSender;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class RetryClientTest {
    private final Request request = new Request("query");
    private final RetryClient client = new RetryClient(Ticker.systemTicker());
    private final LatencyBudget latency = new LatencyBudget(Duration.ofSeconds(5));
    private final SubrequestResult.Success success = new SubrequestResult.Success(201, "payload");

    static Stream<Arguments> categoryLimits() {
        return Stream.of(0, 1, 2).flatMap(limit -> Stream.of(
                Arguments.of(new SubrequestResult.FastError(400), new RetryBudget(10, limit, 10, 10),
                        FailureReason.FAST_ERRORS_EXHAUSTED, Math.max(1, limit)),
                Arguments.of(new SubrequestResult.ServerError(500), new RetryBudget(10, 10, limit, 10),
                        FailureReason.FAILURES_EXHAUSTED, Math.max(1, limit)),
                Arguments.of(new SubrequestResult.Timeout(), new RetryBudget(10, 10, 10, limit),
                        FailureReason.TIMEOUTS_EXHAUSTED, Math.max(1, limit))));
    }

    @ParameterizedTest
    @MethodSource("categoryLimits")
    void stopsExactlyAtIndependentCategoryLimit(SubrequestResult error, RetryBudget budget,
                                                FailureReason reason, int attempts) throws Exception {
        var script = Stream.concat(IntStream.range(0, attempts).mapToObj(i -> reply(error)),
                Stream.of(reply(success))).toArray(ScriptedFakeSender.Step[]::new);
        try (var sender = new ScriptedFakeSender("A", script)) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, new RetrySettings(budget, latency), sender));
            assertEquals(reason, result.reason());
            assertEquals(error, result.lastResult().orElseThrow());
            assertEquals(attempts, result.attempts());
            assertEquals(attempts, sender.calls().size());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void maxAttemptsIncludesInitialSend(int limit) throws Exception {
        var error = new SubrequestResult.ServerError(503);
        var script = Stream.concat(IntStream.range(0, limit).mapToObj(i -> reply(error)),
                Stream.of(reply(success))).toArray(ScriptedFakeSender.Step[]::new);
        try (var sender = new ScriptedFakeSender("A", script)) {
            var result = assertInstanceOf(OperationResult.Failure.class, client.execute(request,
                    new RetrySettings(new RetryBudget(limit, 10, 10, 10), latency), sender));
            assertEquals(FailureReason.ATTEMPTS_EXHAUSTED, result.reason());
            assertEquals(limit, result.attempts());
            assertEquals(limit, sender.calls().size());
        }
    }

    @Test
    void categoriesAreIndependentAndSuccessOnLastAttemptIsAllowed() throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(new SubrequestResult.FastError(429)),
                reply(new SubrequestResult.ServerError(503)), reply(new SubrequestResult.Timeout()),
                reply(success))) {
            assertEquals(new OperationResult.Success(201, "payload", 4), client.execute(request,
                    new RetrySettings(new RetryBudget(4, 2, 2, 2), latency), sender));
            assertEquals(4, sender.calls().size());
        }
    }

    @Test
    void categoryCountersDoNotResetWhenFailureTypeChanges() throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(new SubrequestResult.FastError(400)),
                reply(new SubrequestResult.ServerError(503)), reply(new SubrequestResult.FastError(429)),
                reply(success))) {
            var result = assertInstanceOf(OperationResult.Failure.class, client.execute(request,
                    new RetrySettings(new RetryBudget(5, 2, 3, 3), latency), sender));
            assertEquals(FailureReason.FAST_ERRORS_EXHAUSTED, result.reason());
            assertEquals(3, sender.calls().size());
        }
    }

    @Test
    void waitsForCurrentAttemptBeforeStartingAnother() throws Exception {
        var pending = new CompletableFuture<SubrequestResult>();
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Controlled(pending),
                reply(success)); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> client.execute(request,
                    new RetrySettings(new RetryBudget(3, 3, 3, 3), latency), sender));
            assertTrue(sender.awaitFirstSend(Duration.ofSeconds(3)));
            assertEquals(1, sender.calls().size());
            pending.complete(new SubrequestResult.ServerError(503));
            assertEquals(new OperationResult.Success(201, "payload", 2), result.get(3, TimeUnit.SECONDS));
            assertTrue(sender.calls().get(1).startedAt()
                    - sender.calls().getFirst().completedAt().orElseThrow() >= 0);
        }
    }

    @Test
    void globalDeadlineCancelsActiveAttemptWithoutRetry() throws Exception {
        try (var sender = new ScriptedFakeSender("A", new ScriptedFakeSender.Step.Hang(), reply(success))) {
            var result = assertInstanceOf(OperationResult.Failure.class, client.execute(request,
                    new RetrySettings(new RetryBudget(5, 5, 5, 5),
                            new LatencyBudget(Duration.ofMillis(100), Duration.ofSeconds(5))), sender));
            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(1, sender.calls().size());
            assertTrue(sender.calls().getFirst().cancelledAt().isPresent());
        }
    }

    @Test
    void globalBudgetIsNotRestartedBetweenAttempts() throws Exception {
        var time = new ManualTime();
        var timedClient = new RetryClient(time);
        var error = new SubrequestResult.ServerError(503);
        try (var fake = new ScriptedFakeSender("A", reply(error), reply(error), reply(success))) {
            SubrequestSender sender = value -> {
                var call = fake.send(value);
                time.advance(Duration.ofMillis(60));
                return call;
            };
            var result = assertInstanceOf(OperationResult.Failure.class, timedClient.execute(request,
                    new RetrySettings(new RetryBudget(5, 5, 5, 5),
                            new LatencyBudget(Duration.ofMillis(100), Duration.ofSeconds(1))), sender));
            assertEquals(FailureReason.DEADLINE_EXCEEDED, result.reason());
            assertEquals(2, fake.calls().size());
            assertInstanceOf(SubrequestResult.Timeout.class, result.lastResult().orElseThrow());
        }
    }

    private static ScriptedFakeSender.Step.Reply reply(SubrequestResult outcome) {
        return new ScriptedFakeSender.Step.Reply(outcome);
    }

    @Test
    void zeroCategoryBudgetsStillAllowInitialSuccess() throws Exception {
        try (var sender = new ScriptedFakeSender("A", reply(success))) {
            assertEquals(new OperationResult.Success(201, "payload", 1), client.execute(request,
                    new RetrySettings(new RetryBudget(3, 0, 0, 0), latency), sender));
            assertEquals(1, sender.calls().size());
        }
    }
}
