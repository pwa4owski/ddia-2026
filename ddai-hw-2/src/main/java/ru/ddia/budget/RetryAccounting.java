package ru.ddia.budget;

import ru.ddia.api.FailureReason;
import ru.ddia.api.SubrequestResult;
import java.util.Objects;
import java.util.Optional;

/** Mutable accounting owned exclusively by one logical request. */
public final class RetryAccounting {
    private final RetryBudget budget;
    private int attempts;
    private int fastErrors;
    private int failures;
    private int timeouts;

    public RetryAccounting(RetryBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public void startAttempt() {
        attempts++;
    }

    public int attempts() {
        return attempts;
    }

    public Optional<FailureReason> record(SubrequestResult outcome) {
        Optional<FailureReason> category = switch (outcome) {
            case SubrequestResult.FastError ignored -> ++fastErrors >= budget.maxFastErrors()
                    ? Optional.of(FailureReason.FAST_ERRORS_EXHAUSTED) : Optional.empty();
            case SubrequestResult.ServerError ignored -> ++failures >= budget.maxFailures()
                    ? Optional.of(FailureReason.FAILURES_EXHAUSTED) : Optional.empty();
            case SubrequestResult.Timeout ignored -> ++timeouts >= budget.maxTimeouts()
                    ? Optional.of(FailureReason.TIMEOUTS_EXHAUSTED) : Optional.empty();
            case SubrequestResult.Success ignored -> Optional.empty();
        };
        return attempts >= budget.maxAttempts()
                ? Optional.of(FailureReason.ATTEMPTS_EXHAUSTED) : category;
    }
}
