package ru.ddia.api;

/** Multi-client strategies receive endpoints in their intended rotation order. */
@FunctionalInterface
public interface ResilienceMultiClient<S> {
    OperationResult execute(Request request, S settings, Endpoints endpoints)
            throws InterruptedException;
}
