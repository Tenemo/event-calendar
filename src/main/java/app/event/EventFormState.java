package app.event;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

public final class EventFormState implements Serializable {
    private static final int MAXIMUM_DEFAULT_TIME_SEARCH_HOURS = 72;

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
        reset(timeZone, Clock.systemUTC());
    }

    void reset(String timeZone, Clock clock) {
        selectedEventId = null;
        selectedEventVersion = null;
        title = null;
        description = null;
        location = null;
        ZoneId zoneId = ZoneId.of(timeZone == null ? "UTC" : timeZone);
        LocalDateTime nextHour = LocalDateTime.now(clock.withZone(zoneId))
                .plusHours(1)
                .truncatedTo(ChronoUnit.HOURS);
        nextHour = nextValidUnambiguousTime(nextHour, zoneId);
        startTime = nextHour;
        endTime = nextHour.plusHours(1);
        firstDay = nextHour.toLocalDate();
        lastDay = nextHour.toLocalDate();
        setAllDay(false);
    }

    private LocalDateTime nextValidUnambiguousTime(
            LocalDateTime initialCandidate,
            ZoneId zoneId) {
        LocalDateTime candidate = initialCandidate;
        for (int searchedHours = 0;
                searchedHours < MAXIMUM_DEFAULT_TIME_SEARCH_HOURS;
                searchedHours++) {
            LocalDateTime candidateEndTime = candidate.plusHours(1);
            List<ZoneOffset> candidateOffsets = zoneId.getRules().getValidOffsets(candidate);
            List<ZoneOffset> candidateEndOffsets = zoneId.getRules()
                    .getValidOffsets(candidateEndTime);
            if (candidateOffsets.size() == 1
                    && candidateEndOffsets.size() == 1
                    && candidate.toInstant(candidateOffsets.getFirst()).plus(Duration.ofHours(1))
                            .equals(candidateEndTime.toInstant(candidateEndOffsets.getFirst()))) {
                return candidate;
            }
            candidate = candidate.plusHours(1);
        }
        throw new IllegalStateException(
                "Could not find an unambiguous default event time in the calendar time zone.");
    }

    public void select(CalendarEventItem event) {
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

    public void applyAllDaySelection(String timeZone) {
        if (allDay != allDaySelection) {
            changeAllDayMode(timeZone);
        }
    }

    public void changeAllDayMode(String timeZone) {
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

        ZoneId zoneId = ZoneId.of(timeZone);
        if (firstDay != null) {
            startTime = firstUnambiguousTimeAtOrAfterStartOfDay(firstDay, zoneId);
        }
        if (lastDay != null) {
            endTime = firstUnambiguousTimeAtOrAfterStartOfDay(lastDay.plusDays(1), zoneId);
        }
    }

    private LocalDateTime firstUnambiguousTimeAtOrAfterStartOfDay(
            LocalDate calendarDate,
            ZoneId zoneId) {
        ZoneRules zoneRules = zoneId.getRules();
        LocalDateTime candidate = calendarDate.atStartOfDay();
        for (int transitionCount = 0; transitionCount < 4; transitionCount++) {
            List<ZoneOffset> validOffsets = zoneRules.getValidOffsets(candidate);
            if (validOffsets.size() == 1) {
                return candidate;
            }

            ZoneOffsetTransition transition = zoneRules.getTransition(candidate);
            if (transition == null) {
                throw new IllegalStateException(
                        "Could not resolve the all-day boundary in the calendar time zone.");
            }
            candidate = transition.isGap()
                    ? transition.getDateTimeAfter()
                    : transition.getDateTimeBefore();
        }
        throw new IllegalStateException(
                "Could not find an unambiguous all-day boundary in the calendar time zone.");
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

    void setAllDay(boolean allDay) {
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
