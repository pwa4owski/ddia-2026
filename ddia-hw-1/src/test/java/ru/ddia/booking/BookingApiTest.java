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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    void concurrentRequestsCannotDoubleBook() {
        String payload = body(UUID.randomUUID().toString(), 14);
        var first = client.sendAsync(request("POST", "/bookings", payload), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("POST", "/bookings", payload), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertEquals(java.util.Set.of(201, 409),
                java.util.Set.of(first.join().statusCode(), second.join().statusCode()));
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
