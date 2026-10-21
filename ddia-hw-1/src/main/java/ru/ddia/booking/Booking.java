package ru.ddia.booking;

import java.time.LocalDate;

public record Booking(long id, String room, LocalDate date, int hour) {
}
