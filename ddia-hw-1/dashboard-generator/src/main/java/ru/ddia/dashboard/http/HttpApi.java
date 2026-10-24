package ru.ddia.dashboard.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** HTTP transport and binding of response bodies to typed API models. */
public final class HttpApi {
    private final HttpClient client;
    private final URI baseUrl;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public HttpApi(HttpClient client, URI baseUrl, String token) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public <T> T get(String path, Class<T> responseType) throws IOException, InterruptedException {
        var response = request("GET", path, null);
        requireSuccess(response, "GET", path);
        return mapper.readValue(response.body(), responseType);
    }

    public <T> Optional<T> find(String path, Class<T> responseType) throws IOException, InterruptedException {
        var response = request("GET", path, null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response, "GET", path);
        return Optional.of(mapper.readValue(response.body(), responseType));
    }

    public void send(String method, String path, String body) throws IOException, InterruptedException {
        requireSuccess(request(method, path, body), method, path);
    }

    private HttpResponse<String> request(String method, String path, String body) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl.toString().replaceAll("/+$", "") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void requireSuccess(HttpResponse<String> response, String method, String path) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + path + ": HTTP " + response.statusCode());
        }
    }
}
