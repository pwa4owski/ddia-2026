package ru.ddia.booking;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "metrics.pod=test-pod-7", "metrics.datacenter=test-dc", "metrics.deployment=test-deployment"})
class BookingApiTest {
    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void createReadMoveAndReuseReleasedSlot() throws Exception {
        String room = UUID.randomUUID().toString();
        var created = send("POST", "/bookings", body(room, 14));
        assertEquals(201, created.statusCode());
        String location = created.headers().firstValue("Location").orElseThrow();
        assertEquals(created.body(), send("GET", location, null).body());
        assertEquals(409, send("POST", "/bookings", body(room, 14)).statusCode());
        assertEquals(200, send("PUT", location, body(room, 15)).statusCode());
        assertTrue(send("GET", location, null).body().contains("\"hour\":15"));
        assertEquals(201, send("POST", "/bookings", body(room, 14)).statusCode());
        assertEquals(409, send("PUT", location, body(room, 14)).statusCode());
        assertTrue(send("GET", location, null).body().contains("\"hour\":15"));
    }

    @Test
    void invalidAndMissingBookings() throws Exception {
        for (String payload : new String[] {
                body("A", 24), body("A", -1), body(" ", 14),
                "{\"room\":\"A\",\"date\":\"2026-02-30\",\"hour\":14}",
                "{\"room\":\"A\"}", "{broken"}) {
            assertEquals(400, send("POST", "/bookings", payload).statusCode(), payload);
        }
        assertEquals(404, send("GET", "/bookings/9223372036854775807", null).statusCode());
        assertEquals(404, send("PUT", "/bookings/9223372036854775807", body("A", 14)).statusCode());
    }

    @Test
    void exportsRequestMetricsWithRouteTemplatesAndStatuses() throws Exception {
        String before = metrics();
        String room = UUID.randomUUID().toString();
        var first = send("POST", "/bookings", body(room, 14));
        var second = send("POST", "/bookings", body(room, 15));
        assertEquals(201, first.statusCode());
        assertEquals(201, second.statusCode());
        String firstLocation = first.headers().firstValue("Location").orElseThrow();
        String secondLocation = second.headers().firstValue("Location").orElseThrow();
        assertEquals(200, send("GET", firstLocation, null).statusCode());
        assertEquals(200, send("GET", secondLocation, null).statusCode());
        assertEquals(404, send("GET", "/bookings/9223372036854775807", null).statusCode());

        String after = metrics();
        assertEquals(2, count(after, "GET", "/bookings/{id}", 200) - count(before, "GET", "/bookings/{id}", 200));
        assertEquals(1, count(after, "GET", "/bookings/{id}", 404) - count(before, "GET", "/bookings/{id}", 404));

        assertTrue(after.lines().anyMatch(line -> line.startsWith("bookings_request_seconds_bucket{")
                && !line.contains("le=\"+Inf\"")));
        assertTrue(after.lines().filter(line -> line.startsWith("bookings_request_seconds"))
                .allMatch(line -> line.contains("pod=\"test-pod-7\"")
                        && line.contains("datacenter=\"test-dc\"")
                        && line.contains("deployment=\"test-deployment\"")));
        assertFalse(after.contains("route=\"" + firstLocation + "\""));
        assertFalse(after.contains("route=\"" + secondLocation + "\""));
    }

    @Test
    void concurrentRequestsCannotDoubleBook() {
        String payload = body(UUID.randomUUID().toString(), 14);
        var first = client.sendAsync(request("POST", "/bookings", payload), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("POST", "/bookings", payload), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertEquals(java.util.Set.of(201, 409),
                java.util.Set.of(first.join().statusCode(), second.join().statusCode()));
    }

    private String metrics() throws Exception {
        var response = send("GET", "/actuator/prometheus", null);
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private double count(String exposition, String method, String route, int status) {
        return exposition.lines()
                .filter(line -> line.startsWith("bookings_request_seconds_count{")
                        && line.contains("method=\"" + method + "\"")
                        && line.contains("route=\"" + route + "\"")
                        && line.contains("status=\"" + status + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst().orElse(0);
    }

    private String body(String room, int hour) {
        return "{\"room\":\"%s\",\"date\":\"2026-10-23\",\"hour\":%d}".formatted(room, hour);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
