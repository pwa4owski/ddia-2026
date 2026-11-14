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
import ru.ddia.strategy.ExponentialBackoff;
import ru.ddia.support.ManualTime;
import ru.ddia.support.ScriptedFakeSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class RoundRobinClientTest {
    private final Request request = new Request("query");
    private final ManualTime time = new ManualTime();
    private final RoundRobinClient client = new RoundRobinClient(time, time);
    private final List<ScriptedFakeSender.Invocation> journal = new CopyOnWriteArrayList<>();
    private final SubrequestResult.Success success = new SubrequestResult.Success(200, "answer");
    private final SubrequestResult.ServerError failure = new SubrequestResult.ServerError(503);

    @Test
    void rotatesAcrossAllOutcomesAndWrapsAround() throws Exception {
        try (var a = sender("A", failure, success);
             var b = sender("B", new SubrequestResult.FastError(429));
             var c = sender("C", new SubrequestResult.Timeout())) {
            assertEquals(new OperationResult.Success(200, "answer", 4),
                    client.execute(request, settings(4), endpoints(a, b, c)));
            assertEquals(List.of("A", "B", "C", "A"), order());
            assertTrue(journal.stream().allMatch(call -> call.request().equals(request)));
        }
    }

    @Test
    void firstSuccessDoesNotContactOtherEndpoints() throws Exception {
        try (var a = sender("A", success); var b = sender("B")) {
            assertEquals(new OperationResult.Success(200, "answer", 1),
                    client.execute(request, settings(4), endpoints(a, b)));
            assertEquals(List.of("A"), order());
        }
    }

    @Test
    void oneEndpointSupportsRepeatedAttempts() throws Exception {
        try (var a = sender("A", failure, success)) {
            assertEquals(new OperationResult.Success(200, "answer", 2),
                    client.execute(request, settings(2), endpoints(a)));
            assertEquals(List.of("A", "A"), order());
        }
    }

    @Test
    void attemptBudgetIsSharedAcrossEndpoints() throws Exception {
        try (var a = sender("A", failure); var b = sender("B", failure); var c = sender("C")) {
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, settings(2), endpoints(a, b, c)));
            assertEquals(FailureReason.ATTEMPTS_EXHAUSTED, result.reason());
            assertEquals(2, result.attempts());
            assertEquals(failure, result.lastResult().orElseThrow());
            assertEquals(List.of("A", "B"), order());
        }
    }

    @Test
    void failureBudgetDoesNotResetOnEndpointChange() throws Exception {
        try (var a = sender("A", failure); var b = sender("B", failure); var c = sender("C")) {
            var limited = new RoundRobinSettings(new RetryBudget(5, 5, 2, 5), latency());
            var result = assertInstanceOf(OperationResult.Failure.class,
                    client.execute(request, limited, endpoints(a, b, c)));
            assertEquals(FailureReason.FAILURES_EXHAUSTED, result.reason());
            assertEquals(List.of("A", "B"), order());
        }
    }

    @Test
    void backoffSeriesContinuesAcrossEndpointsAndFastErrorResetsIt() throws Exception {
        try (var a = sender("A", failure, new SubrequestResult.FastError(429), success);
             var b = sender("B", failure, failure)) {
            var settings = new RoundRobinSettings(new RetryBudget(5, 5, 5, 5), latency(),
                    new ExponentialBackoff(Duration.ofMillis(10), 2, Duration.ofMillis(100)));
            assertEquals(new OperationResult.Success(200, "answer", 5),
                    client.execute(request, settings, endpoints(a, b)));
            assertEquals(List.of("A", "B", "A", "B", "A"), order());
            assertEquals(List.of(Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(10)),
                    time.sleeps());
        }
    }

    @Test
    void timedOutCallIsCancelledBeforeNextEndpointStarts() throws Exception {
        var realClient = new RoundRobinClient(Ticker.systemTicker(), Sleeper.systemSleeper());
        try (var a = new ScriptedFakeSender("A", List.of(new ScriptedFakeSender.Step.Hang()), journal);
             var b = sender("B", success)) {
            var settings = new RoundRobinSettings(new RetryBudget(2, 2, 2, 2),
                    new LatencyBudget(Duration.ofSeconds(5), Duration.ofMillis(100)));
            assertEquals(new OperationResult.Success(200, "answer", 2),
                    realClient.execute(request, settings, endpoints(a, b)));
            assertEquals(List.of("A", "B"), order());
            assertTrue(b.calls().getFirst().startedAt() - a.calls().getFirst().cancelledAt().orElseThrow() >= 0);
        }
    }

    @Test
    void sharedClientStartsEveryConcurrentRequestAtZero() throws Exception {
        var realClient = new RoundRobinClient(Ticker.systemTicker(), Sleeper.systemSleeper());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<OperationResult>>();
            for (int i = 0; i < 12; i++) {
                results.add(workers.submit(() -> {
                    var localJournal = new CopyOnWriteArrayList<ScriptedFakeSender.Invocation>();
                    try (var a = new ScriptedFakeSender("A", List.of(reply(failure)), localJournal);
                         var b = new ScriptedFakeSender("B", List.of(reply(success)), localJournal)) {
                        var result = realClient.execute(request, settings(2), endpoints(a, b));
                        assertEquals(List.of("A", "B"), localJournal.stream()
                                .map(ScriptedFakeSender.Invocation::endpointId).toList());
                        return result;
                    }
                }));
            }
            for (var result : results) {
                assertEquals(new OperationResult.Success(200, "answer", 2), result.get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void rejectsEmptyDuplicateAndNullEndpointsAndNullPolicy() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Endpoints(List.of()));
        try (var a = sender("A")) {
            var endpoint = new Endpoint("A", a);
            assertThrows(IllegalArgumentException.class, () -> new Endpoints(List.of(endpoint, endpoint)));
            assertThrows(NullPointerException.class, () -> new Endpoint("A", null));
            assertThrows(NullPointerException.class, () -> client.execute(request, settings(2), null));
        }
        assertThrows(NullPointerException.class,
                () -> new RoundRobinSettings(new RetryBudget(2, 2, 2, 2), latency(), null));
    }

    private ScriptedFakeSender sender(String id, SubrequestResult... results) {
        var steps = new ArrayList<ScriptedFakeSender.Step>();
        for (var result : results) {
            steps.add(reply(result));
        }
        return new ScriptedFakeSender(id, steps, journal);
    }

    private static ScriptedFakeSender.Step.Reply reply(SubrequestResult result) {
        return new ScriptedFakeSender.Step.Reply(result);
    }

    private Endpoints endpoints(ScriptedFakeSender... senders) {
        var values = new ArrayList<Endpoint>();
        for (int i = 0; i < senders.length; i++) {
            values.add(new Endpoint("endpoint-" + i, senders[i]));
        }
        return new Endpoints(values);
    }

    private RoundRobinSettings settings(int attempts) {
        return new RoundRobinSettings(new RetryBudget(attempts, 10, 10, 10), latency());
    }

    private LatencyBudget latency() {
        return new LatencyBudget(Duration.ofSeconds(5));
    }

    private List<String> order() {
        return journal.stream().map(ScriptedFakeSender.Invocation::endpointId).toList();
    }
}
