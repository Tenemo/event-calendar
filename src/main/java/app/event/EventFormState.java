package app.event;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class EventFormState implements Serializable {
    private Long selectedEventId;
    private Integer selectedEventVersion;
    private String title;
    private String description;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDate firstDay;
    private LocalDate lastDay;
    private boolean allDay;
    private boolean allDaySelection;

    public void reset(String timeZone) {
        selectedEventId = null;
        selectedEventVersion = null;
        title = null;
        description = null;
        location = null;
        LocalDateTime nextHour = LocalDateTime.now(
                        ZoneId.of(timeZone == null ? "UTC" : timeZone))
                .plusHours(1)
                .truncatedTo(ChronoUnit.HOURS);
        startTime = nextHour;
        endTime = nextHour.plusHours(1);
        firstDay = nextHour.toLocalDate();
        lastDay = nextHour.toLocalDate();
        setAllDay(false);
    }

    public void select(CalendarEventRow event) {
        selectedEventId = event.getId();
        selectedEventVersion = event.getVersion();
        title = event.getTitle();
        description = event.getDescription();
        location = event.getLocation();
        startTime = event.getStartTime();
        endTime = event.getEndTime();
        firstDay = event.getStartTime().toLocalDate();
        lastDay = event.getInclusiveEndDate();
        setAllDay(event.isAllDay());
    }

    public void applyAllDaySelection() {
        if (allDay != allDaySelection) {
            changeAllDayMode();
        }
    }

    public void changeAllDayMode() {
        allDay = allDaySelection;
        if (allDay) {
            LocalDate selectedFirstDay =
                    startTime == null ? null : startTime.toLocalDate();
            LocalDate selectedLastDay =
                    inclusiveEndDateForTimedRange(selectedFirstDay);
            if (selectedFirstDay != null) {
                firstDay = selectedFirstDay;
            }
            if (selectedLastDay != null) {
                lastDay = selectedLastDay;
            }
            return;
        }

        if (firstDay != null) {
            startTime = firstDay.atStartOfDay();
        }
        if (lastDay != null) {
            endTime = lastDay.plusDays(1).atStartOfDay();
        }
    }

    public EventTimeInput toTimeInput() {
        return allDay
                ? new EventTimeInput.AllDay(firstDay, lastDay)
                : new EventTimeInput.Timed(startTime, endTime);
    }

    private LocalDate inclusiveEndDateForTimedRange(LocalDate selectedFirstDay) {
        if (endTime == null) {
            return null;
        }

        LocalDate inclusiveEndDate = endTime.toLocalDate();
        if (endTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                && startTime != null
                && endTime.isAfter(startTime)) {
            LocalDate previousDay = inclusiveEndDate.minusDays(1);
            if (selectedFirstDay == null
                    || !previousDay.isBefore(selectedFirstDay)) {
                return previousDay;
            }
        }
        return inclusiveEndDate;
    }

    public Long getSelectedEventId() {
        return selectedEventId;
    }

    public Integer getSelectedEventVersion() {
        return selectedEventVersion;
    }

    public boolean isEditing() {
        return selectedEventId != null;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDate getFirstDay() {
        return firstDay;
    }

    public void setFirstDay(LocalDate firstDay) {
        this.firstDay = firstDay;
    }

    public LocalDate getLastDay() {
        return lastDay;
    }

    public void setLastDay(LocalDate lastDay) {
        this.lastDay = lastDay;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public void setAllDay(boolean allDay) {
        this.allDay = allDay;
        allDaySelection = allDay;
    }

    public boolean isAllDaySelection() {
        return allDaySelection;
    }

    public void setAllDaySelection(boolean allDaySelection) {
        this.allDaySelection = allDaySelection;
    }
}
