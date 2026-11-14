package ru.ddia.internal;

import ru.ddia.api.SubrequestCall;
import ru.ddia.api.SubrequestResult;
import ru.ddia.budget.Deadline;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds waiting without assuming that Future.cancel interrupts transport work. */
public final class SubrequestAwaiter {
    private SubrequestAwaiter() {
    }

    public static SubrequestResult await(SubrequestCall call, Deadline global, Deadline attempt)
            throws InterruptedException {
        boolean completed = false;
        try {
            long remaining = Math.min(global.remainingNanos(), attempt.remainingNanos());
            if (remaining == 0) {
                return new SubrequestResult.Timeout();
            }
            SubrequestResult result = Objects.requireNonNull(
                    call.completion().toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS),
                    "Transport result");
            if (global.isExpired() || attempt.isExpired()) {
                return new SubrequestResult.Timeout();
            }
            completed = true;
            return result;
        } catch (TimeoutException exception) {
            return new SubrequestResult.Timeout();
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Transport completed exceptionally", exception.getCause());
        } finally {
            if (!completed) {
                call.cancel();
            }
        }
    }
}
