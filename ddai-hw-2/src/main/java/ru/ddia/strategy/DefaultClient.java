package ru.ddia.strategy;

import ru.ddia.api.OperationResult;
import ru.ddia.api.Request;
import ru.ddia.api.ResilienceClient;
import ru.ddia.api.SubrequestSender;
import ru.ddia.budget.RetryBudget;
import ru.ddia.budget.Ticker;
import ru.ddia.internal.SequentialExecutor;
import ru.ddia.internal.Sleeper;
import java.util.Objects;

/** Stateless, thread-safe client; each invocation owns its deadlines and call. */
public final class DefaultClient implements ResilienceClient<DefaultSettings> {
    private final SequentialExecutor executor;

    public DefaultClient(Ticker ticker) {
        executor = new SequentialExecutor(ticker, Sleeper.systemSleeper());
    }

    @Override
    public OperationResult execute(Request request, DefaultSettings settings, SubrequestSender sender)
            throws InterruptedException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(sender, "sender");
        return executor.execute(request, new RetryBudget(1, 0, 0, 0), settings.latencyBudget(),
                RetryDelayPolicy.immediate(), ignored -> sender);
    }
}
