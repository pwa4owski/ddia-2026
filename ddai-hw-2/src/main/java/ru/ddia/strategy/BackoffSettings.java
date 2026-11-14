package ru.ddia.strategy;

import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import java.util.Objects;

public record BackoffSettings(RetryBudget retryBudget, LatencyBudget latencyBudget,
                              ExponentialBackoff backoff) {
    public BackoffSettings {
        Objects.requireNonNull(retryBudget, "retryBudget");
        Objects.requireNonNull(latencyBudget, "latencyBudget");
        Objects.requireNonNull(backoff, "backoff");
    }
}
