package ru.ddia.api;

import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiContractTest {
    private final SubrequestSender unusedSender = _ -> {
        throw new AssertionError("Validation must not send a request");
    };

    @Test
    void endpointCollectionPreservesOrderAndTakesImmutableCopy() {
        var a = new Endpoint("A", unusedSender);
        var b = new Endpoint("B", unusedSender);
        var source = new ArrayList<>(List.of(a, b));
        var endpoints = new Endpoints(source);
        source.clear();

        assertEquals(List.of(a, b), endpoints.values());
        assertThrows(UnsupportedOperationException.class, () -> endpoints.values().clear());
        assertEquals(List.of(a), new Endpoints(List.of(a)).values());
    }

    @Test
    void rejectsEmptyEndpointsDuplicateIdsAndNullSenders() {
        assertThrows(IllegalArgumentException.class, () -> new Endpoints(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Endpoints(List.of(new Endpoint("A", unusedSender),
                        new Endpoint("A", unusedSender))));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint(" ", unusedSender));
        assertThrows(NullPointerException.class, () -> new Endpoint("A", null));
        var withNull = new ArrayList<Endpoint>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new Endpoints(withNull));
    }

    @Test
    void rejectsNullPayloadAndStatusCategoryMismatch() {
        assertThrows(NullPointerException.class, () -> new Request(null));
        assertThrows(NullPointerException.class, () -> new SubrequestResult.Success(200, null));
        assertThrows(IllegalArgumentException.class, () -> new SubrequestResult.Success(300, "value"));
        assertThrows(IllegalArgumentException.class, () -> new SubrequestResult.FastError(500));
        assertThrows(IllegalArgumentException.class, () -> new SubrequestResult.ServerError(400));
    }

    @Test
    void attemptBudgetIncludesFirstSendAndAcceptsZeroErrorBudgets() {
        var budget = new RetryBudget(1, 0, 0, 0);
        assertEquals(1, budget.maxAttempts());
        assertEquals(0, budget.maxFastErrors());
        assertEquals(0, budget.maxFailures());
        assertEquals(0, budget.maxTimeouts());
        assertThrows(IllegalArgumentException.class, () -> new RetryBudget(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetryBudget(1, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetryBudget(1, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetryBudget(1, 0, 0, -1));
    }

    @Test
    void timeBudgetsMustBePositiveFiniteNanosecondDurations() {
        Duration valid = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new LatencyBudget(Duration.ZERO, valid));
        assertThrows(IllegalArgumentException.class, () -> new LatencyBudget(valid, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LatencyBudget(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyBudget(Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(NullPointerException.class, () -> new LatencyBudget(null));
        assertEquals(new LatencyBudget(valid, valid), new LatencyBudget(valid));
    }
}
