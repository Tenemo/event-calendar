package app.event;

import java.util.List;

public record CalendarEventPage(List<CalendarEvent> events, boolean hasMore) {
    public CalendarEventPage {
        events = List.copyOf(events);
    }
}
