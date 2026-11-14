package ru.ddia.api;

import java.util.Objects;
import java.util.Optional;

/** A logical operation result; attempts counts actual send invocations. */
public sealed interface OperationResult {
    int attempts();

    record Success(int statusCode, String payload, int attempts) implements OperationResult {
        public Success {
            if (statusCode < 200 || statusCode > 299) {
                throw new IllegalArgumentException("Success requires a 2xx status");
            }
            Objects.requireNonNull(payload, "payload");
            if (attempts < 1) {
                throw new IllegalArgumentException("Success requires at least one attempt");
            }
        }
    }

    record Failure(FailureReason reason, int attempts,
                   Optional<SubrequestResult> lastResult) implements OperationResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(lastResult, "lastResult");
            if (attempts < 0) {
                throw new IllegalArgumentException("Attempts must not be negative");
            }
        }
    }
}
