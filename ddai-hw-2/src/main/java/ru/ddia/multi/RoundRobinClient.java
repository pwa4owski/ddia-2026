package ru.ddia.multi;

import ru.ddia.api.Endpoints;
import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.ResilienceMultiClient;
import ru.ddia.budget.Ticker;
import ru.ddia.internal.SequentialExecutor;
import ru.ddia.internal.Sleeper;
import java.util.Objects;

/** Every invocation starts at endpoint zero; there is no shared rotation cursor. */
public final class RoundRobinClient implements ResilienceMultiClient<RoundRobinSettings> {
    private final SequentialExecutor executor;

    public RoundRobinClient(Ticker ticker, Sleeper sleeper) {
        executor = new SequentialExecutor(ticker, sleeper);
    }

    @Override
    public OperationResult execute(Request request, RoundRobinSettings settings, Endpoints endpoints)
            throws InterruptedException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(endpoints, "endpoints");
        var ordered = endpoints.values();
        return executor.execute(request, settings.retryBudget(), settings.latencyBudget(),
                settings.delayPolicy(), attempts -> ordered.get(attempts % ordered.size()).sender());
    }
}
