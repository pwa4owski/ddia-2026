package ru.ddia.strategy;

import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.ResilienceClient;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.Ticker;
import ru.ddia.internal.SequentialExecutor;
import ru.ddia.internal.Sleeper;
import java.util.Objects;

public final class RetryClient implements ResilienceClient<RetrySettings> {
    private final SequentialExecutor executor;

    public RetryClient(Ticker ticker) {
        executor = new SequentialExecutor(ticker, Sleeper.systemSleeper());
    }

    @Override
    public OperationResult execute(Request request, RetrySettings settings, SubrequestSender sender)
            throws InterruptedException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(sender, "sender");
        return executor.execute(request, settings.retryBudget(), settings.latencyBudget(),
                RetryDelayPolicy.immediate(), ignored -> sender);
    }
}
