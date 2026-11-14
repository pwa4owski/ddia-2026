package ru.ddia.strategy;

import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.ResilienceClient;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.Ticker;
import ru.ddia.internal.SequentialExecutor;
import ru.ddia.internal.Sleeper;
import java.util.Objects;

public final class BackoffClient implements ResilienceClient<BackoffSettings> {
    private final SequentialExecutor executor;

    public BackoffClient(Ticker ticker, Sleeper sleeper) {
        executor = new SequentialExecutor(ticker, sleeper);
    }

    @Override
    public OperationResult execute(Request request, BackoffSettings settings, SubrequestSender sender)
            throws InterruptedException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(sender, "sender");
        return executor.execute(request, settings.retryBudget(), settings.latencyBudget(),
                settings.backoff(), ignored -> sender);
    }
}
