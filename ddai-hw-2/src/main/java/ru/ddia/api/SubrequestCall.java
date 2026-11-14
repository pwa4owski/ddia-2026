package ru.ddia.api;

import java.util.concurrent.CompletionStage;

/** A single asynchronous send and its explicit cancellation handle. */
public interface SubrequestCall {
    /** Returns the same non-null completion stage on every invocation. */
    CompletionStage<SubrequestResult> completion();

    /**
     * Requests cancellation of transport work and releases its resources.
     * Must be thread-safe, idempotent, non-blocking, and must not throw.
     * Cancellation is best effort: a remote operation may already have completed.
     */
    void cancel();
}
