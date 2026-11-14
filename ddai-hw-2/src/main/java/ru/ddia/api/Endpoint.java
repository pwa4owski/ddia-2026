package ru.ddia.api;

import java.util.Objects;

/** The ID remains stable even when a new sender is created for a later request. */
public record Endpoint(String id, SubrequestSender sender) {
    public Endpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sender, "sender");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Endpoint ID must not be blank");
        }
    }
}
