package ru.ddia.booking;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingController {
    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Booking> create(@Valid @RequestBody BookingRequest request) {
        Booking booking = service.create(request);
        return ResponseEntity.created(URI.create("/bookings/" + booking.id())).body(booking);
    }

    @GetMapping("/{id}")
    public Booking get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public Booking update(@PathVariable long id, @Valid @RequestBody BookingRequest request) {
        return service.update(id, request);
    }
}
