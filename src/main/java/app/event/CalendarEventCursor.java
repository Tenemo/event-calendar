package app.event;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

public record CalendarEventCursor(OffsetDateTime startTime, long eventId) implements Serializable {
    public CalendarEventCursor {
        Objects.requireNonNull(startTime, "Event cursor start time is required.");
        if (eventId < 1) {
            throw new IllegalArgumentException("Event cursor identifier must be positive.");
        }
    }

    static CalendarEventCursor after(CalendarEvent event) {
        Objects.requireNonNull(event, "Event is required.");
        if (event.getId() == null) {
            throw new IllegalArgumentException("A persisted event is required for pagination.");
        }
        return new CalendarEventCursor(event.getStartTime(), event.getId());
    }
}
