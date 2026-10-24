package ru.ddia.dashboard.discovery;

/** Application HTTP method and route template obtained from runtime mappings. */
public record Endpoint(String method, String route) {
}
