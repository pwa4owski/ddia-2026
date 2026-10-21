package ru.ddia.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record BookingRequest(
        @NotBlank @Size(max = 100) String room,
        @NotNull LocalDate date,
        @NotNull @Min(0) @Max(23) Integer hour) {
}
