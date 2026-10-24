package ru.ddia.dashboard.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import ru.ddia.dashboard.http.HttpApi;

public final class ApplicationClient {
    private final HttpApi api;

    public ApplicationClient(HttpClient client, URI appUrl) {
        api = new HttpApi(client, appUrl, null);
    }

    public List<Endpoint> endpoints() throws IOException, InterruptedException {
        var response = api.get("/actuator/mappings", MappingsResponse.class);
        var endpoints = new ArrayList<Endpoint>();
        for (var context : response.contexts().values()) {
            for (var servletMappings : context.mappings().dispatcherServlets().values()) {
                for (var mapping : servletMappings) {
                    if (!mapping.isApplicationHandler()) {
                        continue;
                    }
                    var conditions = mapping.details().requestMappingConditions();
                    if (conditions.methods().isEmpty()) {
                        throw new IllegalArgumentException("Application mappings must declare HTTP methods explicitly");
                    }
                    for (String method : conditions.methods()) {
                        for (String pattern : conditions.patterns()) {
                            endpoints.add(new Endpoint(method, pattern));
                        }
                    }
                }
            }
        }
        var result = endpoints.stream().distinct()
                .sorted(Comparator.comparing(Endpoint::route).thenComparing(Endpoint::method)).toList();
        if (result.isEmpty()) {
            throw new IllegalStateException("No application HTTP methods found in runtime mappings");
        }
        return result;
    }

    private record MappingsResponse(Map<String, MappingContext> contexts) {
    }

    private record MappingContext(ServletMappings mappings) {
    }

    private record ServletMappings(Map<String, List<RegisteredMapping>> dispatcherServlets) {
    }

    private record RegisteredMapping(MappingDetails details) {
        boolean isApplicationHandler() {
            return details != null && details.handlerMethod() != null
                    && !details.handlerMethod().className().startsWith("org.springframework.");
        }
    }

    private record MappingDetails(HandlerMethod handlerMethod, MappingConditions requestMappingConditions) {
    }

    private record HandlerMethod(String className) {
    }

    private record MappingConditions(List<String> methods, List<String> patterns) {
    }
}
