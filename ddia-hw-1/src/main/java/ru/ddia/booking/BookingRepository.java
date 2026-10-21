package ru.ddia.booking;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {
    private final JdbcTemplate jdbc;

    public BookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Booking create(BookingRequest request) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bookings (room, booking_date, slot_hour) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.room());
            statement.setDate(2, Date.valueOf(request.date()));
            statement.setInt(3, request.hour());
            return statement;
        }, keys);
        return new Booking(keys.getKey().longValue(), request.room(), request.date(), request.hour());
    }

    public Optional<Booking> find(long id) {
        return jdbc.query("SELECT id, room, booking_date, slot_hour FROM bookings WHERE id = ?",
                (rs, row) -> new Booking(rs.getLong("id"), rs.getString("room"),
                        rs.getDate("booking_date").toLocalDate(), rs.getInt("slot_hour")), id)
                .stream().findFirst();
    }

    public boolean update(long id, BookingRequest request) {
        return jdbc.update("UPDATE bookings SET room = ?, booking_date = ?, slot_hour = ? WHERE id = ?",
                request.room(), request.date(), request.hour(), id) > 0;
    }
}
