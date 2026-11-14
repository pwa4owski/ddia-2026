package ru.ddia.budget;

/** Limits are independent; maxAttempts includes the initial send. */
public record RetryBudget(int maxAttempts, int maxFastErrors, int maxFailures, int maxTimeouts) {
    public RetryBudget {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
        if (maxFastErrors < 0 || maxFailures < 0 || maxTimeouts < 0) {
            throw new IllegalArgumentException("Error budgets must not be negative");
        }
    }
}
