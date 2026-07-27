package app.event;

import java.util.List;
import java.util.Objects;

public record CalendarEventPage(
        List<CalendarEvent> events,
        boolean hasMore,
        CalendarEventCursor nextCursor,
        CalendarEventRevision revision,
        boolean restartRequired) {
    public CalendarEventPage {
        events = List.copyOf(events);
        Objects.requireNonNull(revision, "Event page revision is required.");
        if (restartRequired && (!events.isEmpty() || hasMore || nextCursor != null)) {
            throw new IllegalArgumentException(
                    "A page that requires a restart cannot contain event results.");
        }
    }

    static CalendarEventPage restartRequired(CalendarEventRevision currentRevision) {
        return new CalendarEventPage(List.of(), false, null, currentRevision, true);
    }
}
