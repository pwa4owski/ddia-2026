package ru.ddia.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** An immutable, non-empty, ordered collection with unique endpoint IDs. */
public record Endpoints(List<Endpoint> values) {
    public Endpoints {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one endpoint is required");
        }
        var ids = new HashSet<String>();
        for (Endpoint endpoint : values) {
            if (!ids.add(endpoint.id())) {
                throw new IllegalArgumentException("Duplicate endpoint ID: " + endpoint.id());
            }
        }
    }
}
