package ru.ddia.multi;

import ru.ddia.budget.LatencyBudget;
import java.time.Duration;

public record CircuitSettings(int windowSize, double threshold, Duration cooldown) {
    public CircuitSettings {
        if (windowSize < 1 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Require windowSize >= 1 and threshold in [0, 1]");
        }
        new LatencyBudget(cooldown);
    }
}
