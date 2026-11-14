package ru.ddia.multi;

import ru.ddia.api.Endpoints;
import ru.ddia.api.FailureReason;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.ResilienceMultiClient;
import ru.ddia.api.SubrequestCall;
import ru.ddia.api.SubrequestResult;
import ru.ddia.budget.Deadline;
import ru.ddia.budget.Ticker;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Nonblocking sends run concurrently; only the caller waits, with a bounded poll. */
public final class HedgingClient implements ResilienceMultiClient<HedgingSettings> {
    private final Ticker ticker;

    public HedgingClient(Ticker ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    private record Completion(SubrequestResult result, Throwable error) {
    }

    @Override
    public OperationResult execute(Request request, HedgingSettings settings, Endpoints endpoints)
            throws InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(endpoints, "endpoints");
        var deadline = new Deadline(ticker, settings.totalTimeout());
        long started = ticker.read();
        var calls = new ArrayList<SubrequestCall>();
        var completed = new LinkedBlockingQueue<Completion>();
        Optional<SubrequestResult> last = Optional.empty();
        boolean expanded = false;
        try {
            checkInterrupted();
            if (!deadline.isExpired()) {
                start(request, endpoints, 0, calls, completed);
            }
            while (!deadline.isExpired()) {
                checkInterrupted();
                long delayRemaining = Math.max(0, settings.hedgingDelay().toNanos() - (ticker.read() - started));
                // Observe a queued first success before launching hedges at the delay boundary.
                Completion event = completed.poll();
                if (event == null && !expanded && delayRemaining == 0) {
                    expanded = true;
                    for (int i = 1; i < endpoints.values().size() && !deadline.isExpired(); i++) {
                        checkInterrupted();
                        start(request, endpoints, i, calls, completed);
                    }
                    continue;
                }
                if (event == null) {
                    long wait = deadline.remainingNanos();
                    if (!expanded) {
                        wait = Math.min(wait, delayRemaining);
                    }
                    event = completed.poll(wait, TimeUnit.NANOSECONDS);
                }
                if (event == null || deadline.isExpired()) {
                    continue;
                }
                if (event.error() != null) {
                    throw new IllegalStateException("Transport completed exceptionally", event.error());
                }
                var result = Objects.requireNonNull(event.result(), "Transport result");
                last = Optional.of(result);
                if (result instanceof SubrequestResult.Success success) {
                    return new OperationResult.Success(success.statusCode(), success.payload(), calls.size());
                }
                if (!expanded) {
                    // Early first failure: deliberately start the remaining endpoints immediately.
                    expanded = true;
                    for (int i = 1; i < endpoints.values().size() && !deadline.isExpired(); i++) {
                        checkInterrupted();
                        start(request, endpoints, i, calls, completed);
                    }
                }
            }
            return new OperationResult.Failure(FailureReason.DEADLINE_EXCEEDED, calls.size(), last);
        } finally {
            for (SubrequestCall call : calls) {
                call.cancel();
            }
        }
    }

    private static void start(Request request, Endpoints endpoints, int index,
                              ArrayList<SubrequestCall> calls, LinkedBlockingQueue<Completion> completed) {
        var call = Objects.requireNonNull(endpoints.values().get(index).sender().send(request), "Transport call");
        calls.add(call);
        call.completion().whenComplete((result, error) -> completed.add(new Completion(result, error)));
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Hedging caller interrupted");
        }
    }
}
