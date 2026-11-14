package ru.ddia.internal;

import ru.ddia.api.SubrequestSender;
import ru.ddia.api.SubrequestResult;

/** Selects a sender before each attempt; the index counts sends already started. */
@FunctionalInterface
public interface EndpointSelector {
    SubrequestSender select(int attemptsStarted);

    default void completed(SubrequestResult result) {
    }

    default void release() {
    }
}
