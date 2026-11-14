package ru.ddia.api;

import java.util.Objects;

/** A logical request; transports may send its payload more than once. */
public record Request(String payload) {
    public Request {
        Objects.requireNonNull(payload, "payload");
    }
}
