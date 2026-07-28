package app.event;

import app.calendar.CalendarTimeService;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class CalendarEventItem implements Serializable {
    private static final DateTimeFormatter DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter ALL_DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter FULL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final Long id;
    private final int version;
    private final String title;
    private final String description;
    private final String location;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final boolean allDay;
    private final LocalDate inclusiveEndDate;

    private CalendarEventItem(
            Long id,
            int version,
            String title,
            String description,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean allDay,
            LocalDate inclusiveEndDate) {
        this.id = id;
        this.version = version;
        this.title = title;
        this.description = description;
        this.location = location;
        this.startTime = startTime;
        this.endTime = endTime;
        this.allDay = allDay;
        this.inclusiveEndDate = inclusiveEndDate;
    }

    public static CalendarEventItem from(
            CalendarEvent event,
            String timeZone,
            CalendarTimeService calendarTimeService) {
        LocalDateTime calendarEndTime = calendarTimeService.toCalendarTime(event.getEndTime(), timeZone);
        return new CalendarEventItem(
                event.getId(),
                event.getVersion(),
                event.getTitle(),
                event.getDescription(),
                event.getLocation(),
                calendarTimeService.toCalendarTime(event.getStartTime(), timeZone),
                calendarEndTime,
                event.isAllDay(),
                event.isAllDay()
                        ? calendarTimeService.toCalendarDateImmediatelyBefore(event.getEndTime(), timeZone)
                        : calendarEndTime.toLocalDate());
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

    public LocalDate getInclusiveEndDate() {
        return inclusiveEndDate;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public String getDateLabel() {
        return startTime.format(DATE_LABEL_FORMAT);
    }

    public String getScheduleLabel() {
        if (allDay) {
            LocalDate firstDay = startTime.toLocalDate();
            LocalDate lastDay = getInclusiveEndDate();
            if (!firstDay.equals(lastDay)) {
                return "All day from "
                        + firstDay.format(ALL_DAY_DATE_FORMAT)
                        + " to "
                        + lastDay.format(ALL_DAY_DATE_FORMAT);
            }
            return "All day";
        }
        if (startTime.toLocalDate().equals(endTime.toLocalDate())) {
            return startTime.format(FULL_DATE_FORMAT)
                    + " · "
                    + startTime.format(TIME_FORMAT)
                    + "–"
                    + endTime.format(TIME_FORMAT);
        }
        return startTime.format(FULL_DATE_FORMAT)
                + " at "
                + startTime.format(TIME_FORMAT)
                + " to "
                + endTime.format(FULL_DATE_FORMAT)
                + " at "
                + endTime.format(TIME_FORMAT);
    }

    public String getScheduleAndLocationLabel() {
        if (location == null || location.isBlank()) {
            return getScheduleLabel();
        }
        return getScheduleLabel() + " · " + location;
    }
}
