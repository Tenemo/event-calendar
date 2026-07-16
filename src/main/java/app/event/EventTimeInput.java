package app.event;

import java.time.LocalDate;
import java.time.LocalDateTime;

public sealed interface EventTimeInput permits EventTimeInput.Timed, EventTimeInput.AllDay {
    record Timed(LocalDateTime startTime, LocalDateTime endTime) implements EventTimeInput {
    }

    record AllDay(LocalDate firstDay, LocalDate lastDay) implements EventTimeInput {
    }
}
