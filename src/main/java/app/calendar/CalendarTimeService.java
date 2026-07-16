package app.calendar;

import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@ApplicationScoped
public class CalendarTimeService {
    public String normalizeTimeZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            throw new ValidationException("Time zone is required.");
        }

        String normalizedTimeZone = timeZone.trim();
        try {
            return ZoneId.of(normalizedTimeZone).getId();
        } catch (DateTimeException exception) {
            throw new ValidationException("Time zone must be a valid region such as Europe/Warsaw.");
        }
    }

    public OffsetDateTime toStoredTime(LocalDateTime localDateTime, String timeZone) {
        if (localDateTime == null) {
            return null;
        }

        ZoneId zoneId = ZoneId.of(normalizeTimeZone(timeZone));
        List<ZoneOffset> validOffsets = zoneId.getRules().getValidOffsets(localDateTime);
        if (validOffsets.isEmpty()) {
            throw new ValidationException("The selected time does not exist in the calendar time zone because of a clock change.");
        }
        if (validOffsets.size() > 1) {
            throw new ValidationException("The selected time occurs twice in the calendar time zone because of a clock change. Choose another time.");
        }
        return OffsetDateTime.of(localDateTime, validOffsets.getFirst());
    }

    public OffsetDateTime toStoredStartOfDay(LocalDate calendarDate, String timeZone) {
        if (calendarDate == null) {
            return null;
        }

        ZoneId zoneId = ZoneId.of(normalizeTimeZone(timeZone));
        ZonedDateTime startOfDay = calendarDate.atStartOfDay(zoneId);
        if (!startOfDay.toLocalDate().equals(calendarDate)) {
            throw new ValidationException("The selected date does not exist in the calendar time zone because of a clock change.");
        }
        return startOfDay.toOffsetDateTime();
    }

    public OffsetDateTime toStoredExclusiveDayBoundary(LocalDate exclusiveCalendarDate, String timeZone) {
        if (exclusiveCalendarDate == null) {
            return null;
        }

        ZoneId zoneId = ZoneId.of(normalizeTimeZone(timeZone));
        return exclusiveCalendarDate.atStartOfDay(zoneId).toOffsetDateTime();
    }

    public LocalDate toCalendarDateImmediatelyBefore(OffsetDateTime storedExclusiveEnd, String timeZone) {
        if (storedExclusiveEnd == null) {
            return null;
        }
        return toCalendarTime(storedExclusiveEnd.minusNanos(1), timeZone).toLocalDate();
    }

    public LocalDateTime toCalendarTime(OffsetDateTime storedTime, String timeZone) {
        if (storedTime == null) {
            return null;
        }
        ZoneId zoneId = ZoneId.of(normalizeTimeZone(timeZone));
        return storedTime.atZoneSameInstant(zoneId).toLocalDateTime();
    }
}
