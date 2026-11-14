package ru.ddia.api;

/**
 * A transport scoped to one logical request and all of its subrequests.
 * Implementations start work without blocking and return a non-null handle.
 * Service errors complete normally with a typed result; exceptional completion
 * represents a transport implementation error, not an HTTP outcome.
 */
@FunctionalInterface
public interface SubrequestSender {
    SubrequestCall send(Request request);
}
