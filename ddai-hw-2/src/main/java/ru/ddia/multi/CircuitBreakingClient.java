package ru.ddia.multi;

import ru.ddia.api.Endpoint;
import ru.ddia.api.Endpoints;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.ResilienceMultiClient;
import ru.ddia.api.SubrequestResult;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.Ticker;
import ru.ddia.internal.EndpointSelector;
import ru.ddia.internal.NoHealthyEndpointsException;
import ru.ddia.internal.SequentialExecutor;
import ru.ddia.internal.Sleeper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Registry lifetime is the client lifetime. IDs should belong to a bounded endpoint set. */
public final class CircuitBreakingClient implements ResilienceMultiClient<RoundRobinSettings> {
    private final CircuitSettings settings;
    private final Ticker ticker;
    private final SequentialExecutor executor;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public CircuitBreakingClient(Ticker ticker, Sleeper sleeper, CircuitSettings settings) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.settings = Objects.requireNonNull(settings, "settings");
        executor = new SequentialExecutor(ticker, sleeper);
    }

    @Override
    public OperationResult execute(Request request, RoundRobinSettings retries, Endpoints endpoints)
            throws InterruptedException {
        Objects.requireNonNull(retries, "settings");
        Objects.requireNonNull(endpoints, "endpoints");
        return executor.execute(request, retries.retryBudget(), retries.latencyBudget(), retries.delayPolicy(),
                new EndpointSelector() {
                    private int next;
                    private Entry selected;
                    private long generation;

                    @Override
                    public SubrequestSender select(int attemptsStarted) {
                        for (int i = 0; i < endpoints.values().size(); i++) {
                            Endpoint endpoint = endpoints.values().get(next);
                            next = (next + 1) % endpoints.values().size();
                            Entry entry = entries.computeIfAbsent(endpoint.id(), Entry::new);
                            long acquired = entry.acquire();
                            if (acquired >= 0) {
                                selected = entry;
                                generation = acquired;
                                return endpoint.sender();
                            }
                        }
                        throw new NoHealthyEndpointsException();
                    }

                    @Override
                    public void completed(SubrequestResult result) {
                        selected.complete(generation, result);
                        selected = null;
                    }

                    @Override
                    public void release() {
                        if (selected != null) {
                            selected.release(generation);
                            selected = null;
                        }
                    }
                });
    }

    private final class Entry {
        private final CircuitBreaker breaker;
        private long openedAt;
        private long generation;

        Entry(String id) {
            // Error counts are discrete: choose a boundary halfway before the first forbidden count.
            long forbidden = (long) Math.floor(settings.threshold() * settings.windowSize()) + 1;
            // Multiplication can round down at equality, e.g. 0.58 * 100 < 58.
            // Match the public errors / N comparison before converting to library percentages.
            while ((double) forbidden / settings.windowSize() <= settings.threshold()) {
                forbidden++;
            }
            while (forbidden > 1 && (double) (forbidden - 1) / settings.windowSize() > settings.threshold()) {
                forbidden--;
            }
            float rate = (float) (100.0 * (forbidden - 0.5) / settings.windowSize());
            var config = CircuitBreakerConfig.custom()
                    .slidingWindowSize(settings.windowSize()).minimumNumberOfCalls(settings.windowSize())
                    .failureRateThreshold(Math.max(Float.MIN_NORMAL, Math.min(100, rate)))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .waitDurationInOpenState(Duration.ofNanos(Long.MAX_VALUE))
                    .slowCallDurationThreshold(Duration.ofNanos(Long.MAX_VALUE))
                    .build();
            breaker = CircuitBreaker.of(id, config);
            if (settings.threshold() == 1) {
                breaker.transitionToMetricsOnlyState();
            }
        }

        synchronized long acquire() {
            if (breaker.getState() == CircuitBreaker.State.OPEN) {
                if (ticker.read() - openedAt < settings.cooldown().toNanos()) {
                    return -1;
                }
                breaker.transitionToHalfOpenState();
                generation++;
            }
            return breaker.tryAcquirePermission() ? generation : -1;
        }

        synchronized void complete(long token, SubrequestResult result) {
            if (token != generation) {
                return; // A late call from an earlier circuit generation cannot decide a probe.
            }
            var before = breaker.getState();
            if (result instanceof SubrequestResult.Success) {
                breaker.onSuccess(0, TimeUnit.NANOSECONDS);
            } else {
                breaker.onError(0, TimeUnit.NANOSECONDS, new EndpointFailure());
            }
            if (breaker.getState() != before) {
                generation++;
                if (breaker.getState() == CircuitBreaker.State.OPEN) {
                    openedAt = ticker.read();
                }
            }
        }

        synchronized void release(long token) {
            if (token == generation) {
                breaker.releasePermission();
            }
        }
    }

    private static final class EndpointFailure extends RuntimeException {
        EndpointFailure() {
            super("Endpoint returned 4xx, 5xx or timeout", null, false, false);
        }
    }
}
