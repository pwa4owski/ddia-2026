package ru.ddia.api;

import java.util.Objects;

/** Normal transport outcomes, including a client-observed timeout. */
public sealed interface SubrequestResult {
    record Success(int statusCode, String payload) implements SubrequestResult {
        public Success {
            if (statusCode < 200 || statusCode > 299) {
                throw new IllegalArgumentException("Success requires a 2xx status");
            }
            Objects.requireNonNull(payload, "payload");
        }
    }

    record FastError(int statusCode) implements SubrequestResult {
        public FastError {
            if (statusCode < 400 || statusCode > 499) {
                throw new IllegalArgumentException("FastError requires a 4xx status");
            }
        }
    }

    record ServerError(int statusCode) implements SubrequestResult {
        public ServerError {
            if (statusCode < 500 || statusCode > 599) {
                throw new IllegalArgumentException("ServerError requires a 5xx status");
            }
        }
    }

    record Timeout() implements SubrequestResult {
    }
}
