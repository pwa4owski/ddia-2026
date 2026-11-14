package ru.ddia.internal;

import ru.ddia.api.FailureReason;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.SubrequestResult;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.Deadline;
import ru.ddia.budget.LatencyBudget;
import ru.ddia.budget.RetryAccounting;
import ru.ddia.budget.RetryBudget;
import ru.ddia.budget.Ticker;
import ru.ddia.strategy.RetryDelayPolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Shared loop; budgets and delay-series state belong to each invocation. */
public final class SequentialExecutor {
    private final Ticker ticker;
    private final Sleeper sleeper;

    public SequentialExecutor(Ticker ticker, Sleeper sleeper) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public OperationResult execute(Request request, RetryBudget retries, LatencyBudget latency,
                                   RetryDelayPolicy delays, EndpointSelector selector)
            throws InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(retries, "retries");
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(delays, "delays");
        Objects.requireNonNull(selector, "selector");
        var global = new Deadline(ticker, latency.totalTimeout());
        var accounting = new RetryAccounting(retries);
        Optional<SubrequestResult> lastResult = Optional.empty();
        int slowErrors = 0;

        while (true) {
            checkInterrupted();
            if (global.isExpired()) {
                return new OperationResult.Failure(FailureReason.DEADLINE_EXCEEDED,
                        accounting.attempts(), lastResult);
            }
            SubrequestSender sender;
            try {
                sender = Objects.requireNonNull(selector.select(accounting.attempts()), "sender");
            } catch (NoHealthyEndpointsException exception) {
                return new OperationResult.Failure(FailureReason.NO_HEALTHY_ENDPOINTS,
                        accounting.attempts(), lastResult);
            }
            SubrequestResult result;
            try {
                var attempt = new Deadline(ticker, latency.subrequestTimeout());
                checkInterrupted();
                if (global.isExpired()) {
                    return new OperationResult.Failure(FailureReason.DEADLINE_EXCEEDED,
                            accounting.attempts(), lastResult);
                }
                accounting.startAttempt();
                var call = Objects.requireNonNull(sender.send(request), "Transport call");
                result = SubrequestAwaiter.await(call, global, attempt);
                selector.completed(result);
            } finally {
                selector.release();
            }
            lastResult = Optional.of(result);
            if (result instanceof SubrequestResult.Success success) {
                return new OperationResult.Success(success.statusCode(), success.payload(), accounting.attempts());
            }
            Optional<FailureReason> exhausted = accounting.record(result);
            if (global.isExpired()) {
                return new OperationResult.Failure(FailureReason.DEADLINE_EXCEEDED,
                        accounting.attempts(), lastResult);
            }
            if (exhausted.isPresent()) {
                return new OperationResult.Failure(exhausted.get(), accounting.attempts(), lastResult);
            }
            slowErrors = result instanceof SubrequestResult.FastError ? 0 : slowErrors + 1;
            Duration delay = Objects.requireNonNull(delays.delayAfter(slowErrors), "retry delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("Retry delay must not be negative");
            }
            if (!delay.isZero()) {
                waitForRetry(delay, global);
            }
        }
    }

    private void waitForRetry(Duration duration, Deadline global) throws InterruptedException {
        var pause = new Deadline(ticker, duration);
        long remaining;
        while ((remaining = Math.min(pause.remainingNanos(), global.remainingNanos())) > 0) {
            checkInterrupted();
            sleeper.sleep(Duration.ofNanos(remaining));
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Caller interrupted before sending");
        }
    }
}
