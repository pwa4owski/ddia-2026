package ru.ddia.multi;

import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryBudget;
import ru.ddia.strategy.RetryDelayPolicy;
import java.util.Objects;

/** Budgets cover the whole logical request, across all endpoints. */
public record RoundRobinSettings(RetryBudget retryBudget, LatencyBudget latencyBudget,
                                 RetryDelayPolicy delayPolicy) {
    public RoundRobinSettings {
        Objects.requireNonNull(retryBudget, "retryBudget");
        Objects.requireNonNull(latencyBudget, "latencyBudget");
        Objects.requireNonNull(delayPolicy, "delayPolicy");
    }

    public RoundRobinSettings(RetryBudget retryBudget, LatencyBudget latencyBudget) {
        this(retryBudget, latencyBudget, RetryDelayPolicy.immediate());
    }
}
