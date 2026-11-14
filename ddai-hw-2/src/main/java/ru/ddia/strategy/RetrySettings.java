package ru.ddia.strategy;

import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import java.util.Objects;

public record RetrySettings(RetryBudget retryBudget, LatencyBudget latencyBudget) {
    public RetrySettings {
        Objects.requireNonNull(retryBudget, "retryBudget");
        Objects.requireNonNull(latencyBudget, "latencyBudget");
    }
}
