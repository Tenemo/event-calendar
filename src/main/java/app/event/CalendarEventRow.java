package app.event;

import app.calendar.CalendarTimeService;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class CalendarEventRow implements Serializable {
    private static final DateTimeFormatter DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' HH:mm", Locale.ENGLISH);

    private final Long id;
    private final int version;
    private final String title;
    private final String description;
    private final String location;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final boolean allDay;

    private CalendarEventRow(
            Long id,
            int version,
            String title,
            String description,
            String location,
            LocalDateTime startAt,
            LocalDateTime endAt,
            boolean allDay) {
        this.id = id;
        this.version = version;
        this.title = title;
        this.description = description;
        this.location = location;
        this.startAt = startAt;
        this.endAt = endAt;
        this.allDay = allDay;
    }

    public static CalendarEventRow from(
            CalendarEvent event,
            String timeZone,
            CalendarTimeService calendarTimeService) {
        return new CalendarEventRow(
                event.getId(),
                event.getVersion(),
                event.getTitle(),
                event.getDescription(),
                event.getLocation(),
                calendarTimeService.toCalendarTime(event.getStartAt(), timeZone),
                calendarTimeService.toCalendarTime(event.getEndAt(), timeZone),
                event.isAllDay());
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getLocation() {
        return location;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public String getDateLabel() {
        return startAt.format(DATE_LABEL_FORMAT);
    }

    public String getTimeLabel() {
        if (allDay) {
            return "All day";
        }
        return startAt.format(DATE_TIME_FORMAT) + " to " + endAt.format(DATE_TIME_FORMAT);
    }

    public String getDetailLabel() {
        if (location == null || location.isBlank()) {
            return getTimeLabel();
        }
        return getTimeLabel() + " at " + location;
    }
}
