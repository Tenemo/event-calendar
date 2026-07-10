package app.calendar;

import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

@ApplicationScoped
public class CalendarTimeService {
    public String normalizeTimeZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            throw new ValidationException("Timezone is required.");
        }

        String normalizedTimeZone = timeZone.trim();
        try {
            return ZoneId.of(normalizedTimeZone).getId();
        } catch (DateTimeException exception) {
            throw new ValidationException("Timezone must be a valid region such as Europe/Warsaw.");
        }
    }

    public OffsetDateTime toStoredTime(LocalDateTime localDateTime, String timeZone) {
        if (localDateTime == null) {
            return null;
        }

        ZoneId zoneId = ZoneId.of(normalizeTimeZone(timeZone));
        List<ZoneOffset> validOffsets = zoneId.getRules().getValidOffsets(localDateTime);
        if (validOffsets.isEmpty()) {
            throw new ValidationException("The selected time does not exist in the calendar timezone because of a clock change.");
        }
        if (validOffsets.size() > 1) {
            throw new ValidationException("The selected time occurs twice in the calendar timezone because of a clock change. Choose another time.");
        }
        return OffsetDateTime.of(localDateTime, validOffsets.getFirst());
    }

    public LocalDateTime toCalendarTime(OffsetDateTime storedTime, String timeZone) {
        if (storedTime == null) {
            return null;
        }
        ZoneId zoneId = ZoneId.of(normalizeTimeZone(timeZone));
        return storedTime.atZoneSameInstant(zoneId).toLocalDateTime();
    }
}
