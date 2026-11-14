package ru.ddia.api;

/** Settings contain immutable strategy parameters and budgets. */
@FunctionalInterface
public interface ResilienceClient<S> {
    /** Interruption stops waiting and cancels the active transport call. */
    OperationResult execute(Request request, S settings, SubrequestSender sender)
            throws InterruptedException;
}
