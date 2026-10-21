package ru.ddia.booking;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingService {
    private final BookingRepository repository;

    public BookingService(BookingRepository repository) {
        this.repository = repository;
    }

    public Booking create(BookingRequest request) {
        return repository.create(request);
    }

    public Booking get(long id) {
        return repository.find(id).orElseThrow(this::notFound);
    }

    public Booking update(long id, BookingRequest request) {
        if (!repository.update(id, request)) {
            throw notFound();
        }
        return new Booking(id, request.room(), request.date(), request.hour());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
    }
}
