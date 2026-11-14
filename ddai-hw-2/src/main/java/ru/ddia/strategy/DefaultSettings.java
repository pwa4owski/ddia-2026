package ru.ddia.strategy;

import ru.ddia.budget.LatencyBudget;
import java.util.Objects;

public record DefaultSettings(LatencyBudget latencyBudget) {
    public DefaultSettings {
        Objects.requireNonNull(latencyBudget, "latencyBudget");
    }

    public int maxAttempts() {
        return 1;
    }
}
