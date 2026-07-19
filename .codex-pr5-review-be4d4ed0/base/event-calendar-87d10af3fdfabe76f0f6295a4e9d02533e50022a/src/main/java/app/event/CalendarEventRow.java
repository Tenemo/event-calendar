package app.event;

import app.calendar.CalendarTimeService;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class CalendarEventRow implements Serializable {
    private static final DateTimeFormatter DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter ALL_DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' HH:mm", Locale.ENGLISH);

    private final Long id;
    private final int version;
    private final String title;
    private final String description;
    private final String location;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final boolean allDay;

    private CalendarEventRow(
            Long id,
            int version,
            String title,
            String description,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean allDay) {
        this.id = id;
        this.version = version;
        this.title = title;
        this.description = description;
        this.location = location;
        this.startTime = startTime;
        this.endTime = endTime;
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
                calendarTimeService.toCalendarTime(event.getStartTime(), timeZone),
                calendarTimeService.toCalendarTime(event.getEndTime(), timeZone),
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

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public String getDateLabel() {
        return startTime.format(DATE_LABEL_FORMAT);
    }

    public String getTimeLabel() {
        if (allDay) {
            if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
                return "All day from "
                        + startTime.format(ALL_DAY_DATE_FORMAT)
                        + " to "
                        + endTime.format(ALL_DAY_DATE_FORMAT);
            }
            return "All day";
        }
        return startTime.format(DATE_TIME_FORMAT) + " to " + endTime.format(DATE_TIME_FORMAT);
    }

    public String getDetailLabel() {
        if (location == null || location.isBlank()) {
            return getTimeLabel();
        }
        return getTimeLabel() + " at " + location;
    }
}
