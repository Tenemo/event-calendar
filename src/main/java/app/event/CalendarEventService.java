package app.event;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.calendar.CalendarTimeService;
import app.membership.CalendarAccessService;
import app.user.ApplicationUser;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Stateless
public class CalendarEventService {
    private static final int MAXIMUM_EVENT_TITLE_LENGTH = 200;
    private static final int MAXIMUM_EVENT_LOCATION_LENGTH = 200;

    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarTimeService calendarTimeService;

    @Inject
    private AuditService auditService;

    public List<CalendarEvent> findPublicEvents(String publicToken, OffsetDateTime rangeStartTime, OffsetDateTime rangeEndTime) {
        Calendar calendar = calendarAccessService.requirePublicReadableCalendar(publicToken);
        return findEvents(calendar.getId(), rangeStartTime, rangeEndTime);
    }

    public List<CalendarEvent> findEditorEvents(ApplicationUser user, Long calendarId, OffsetDateTime rangeStartTime, OffsetDateTime rangeEndTime) {
        calendarAccessService.requireCanEdit(user, calendarId);
        return findEvents(calendarId, rangeStartTime, rangeEndTime);
    }

    public CalendarEvent createEvent(
            ApplicationUser actingUser,
            Long calendarId,
            String title,
            String description,
            String location,
            EventTimeInput eventTimeInput,
            Integer expectedCalendarVersion,
            String expectedCalendarTimeZone) {
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        String normalizedTitle = normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        String normalizedLocation = normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer.");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Calendar calendar = calendarService.requireActiveCalendarForEventMutation(calendarId);
        requireExpectedCalendarState(calendar, expectedCalendarVersion, expectedCalendarTimeZone);
        EventTimeRange normalizedTimeRange = normalizeEventTimes(calendar, eventTimeInput);
        CalendarEvent event = new CalendarEvent();
        event.setCalendar(calendar);
        event.setTitle(normalizedTitle);
        event.setDescription(normalizeOptionalText(description));
        event.setLocation(normalizedLocation);
        event.setStartTime(normalizedTimeRange.startTime());
        event.setEndTime(normalizedTimeRange.endTime());
        event.setAllDay(eventTimeInput instanceof EventTimeInput.AllDay);
        event.setCreatedByUser(actingUser);
        event.setUpdatedByUser(actingUser);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        entityManager.persist(event);
        entityManager.flush();
        auditService.record(actingUser, calendar, "calendar_event", event.getId(), "created", "Event created.");
        return event;
    }

    public CalendarEvent updateEvent(
            ApplicationUser actingUser,
            Long eventId,
            Integer expectedVersion,
            String title,
            String description,
            String location,
            EventTimeInput eventTimeInput,
            Integer expectedCalendarVersion,
            String expectedCalendarTimeZone) {
        CalendarEvent event = requireEvent(eventId);
        calendarAccessService.requireCanEdit(actingUser, event.getCalendar().getId());
        requireExpectedVersion(event, expectedVersion);
        String normalizedTitle = normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        String normalizedLocation = normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer.");
        Calendar calendar = calendarService.requireActiveCalendarForEventMutation(event.getCalendar().getId());
        requireExpectedCalendarState(calendar, expectedCalendarVersion, expectedCalendarTimeZone);
        EventTimeRange normalizedTimeRange = normalizeEventTimes(calendar, eventTimeInput);

        event.setTitle(normalizedTitle);
        event.setDescription(normalizeOptionalText(description));
        event.setLocation(normalizedLocation);
        event.setStartTime(normalizedTimeRange.startTime());
        event.setEndTime(normalizedTimeRange.endTime());
        event.setAllDay(eventTimeInput instanceof EventTimeInput.AllDay);
        event.setUpdatedByUser(actingUser);
        event.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actingUser, event.getCalendar(), "calendar_event", event.getId(), "updated", "Event updated.");
        flushWithConflictMessage();
        return event;
    }

    public void deleteEvent(ApplicationUser actingUser, Long eventId, Integer expectedVersion) {
        CalendarEvent event = requireEvent(eventId);
        calendarAccessService.requireCanEdit(actingUser, event.getCalendar().getId());
        requireExpectedVersion(event, expectedVersion);
        auditService.record(actingUser, event.getCalendar(), "calendar_event", event.getId(), "deleted", "Event deleted.");
        entityManager.remove(event);
        flushWithConflictMessage();
    }

    private EventTimeRange normalizeEventTimes(
            Calendar calendar,
            EventTimeInput eventTimeInput) {
        if (eventTimeInput instanceof EventTimeInput.Timed timedInput) {
            return normalizeTimedEventTimes(calendar, timedInput.startTime(), timedInput.endTime());
        }
        if (eventTimeInput instanceof EventTimeInput.AllDay allDayInput) {
            return normalizeAllDayEventDates(calendar, allDayInput.firstDay(), allDayInput.lastDay());
        }
        throw new ValidationException("Event dates are required.");
    }

    private EventTimeRange normalizeTimedEventTimes(
            Calendar calendar,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        if (startTime == null) {
            throw new ValidationException("Event start time is required.");
        }
        if (endTime == null) {
            throw new ValidationException("Event end time is required.");
        }

        String timeZone = calendar.getTimeZone();
        OffsetDateTime storedStartTime = calendarTimeService.toStoredTime(startTime, timeZone);
        OffsetDateTime storedEndTime = calendarTimeService.toStoredTime(endTime, timeZone);
        if (!storedEndTime.isAfter(storedStartTime)) {
            throw new ValidationException("Event end time must be after the start time.");
        }
        return new EventTimeRange(storedStartTime, storedEndTime);
    }

    private EventTimeRange normalizeAllDayEventDates(
            Calendar calendar,
            LocalDate firstDay,
            LocalDate lastDay) {
        if (firstDay == null) {
            throw new ValidationException("Event first day is required.");
        }
        if (lastDay == null) {
            throw new ValidationException("Event last day is required.");
        }

        String timeZone = calendar.getTimeZone();
        if (lastDay.isBefore(firstDay)) {
            throw new ValidationException("Event last day must be on or after the first day.");
        }

        OffsetDateTime storedStartTime = calendarTimeService.toStoredStartOfDay(firstDay, timeZone);
        if (!lastDay.equals(firstDay)) {
            calendarTimeService.toStoredStartOfDay(lastDay, timeZone);
        }
        OffsetDateTime storedEndTime = calendarTimeService.toStoredExclusiveDayBoundary(
                lastDay.plusDays(1),
                timeZone);
        return new EventTimeRange(storedStartTime, storedEndTime);
    }

    private void requireExpectedCalendarState(
            Calendar calendar,
            Integer expectedCalendarVersion,
            String expectedCalendarTimeZone) {
        if (expectedCalendarVersion == null
                || calendar.getVersion() != expectedCalendarVersion
                || !Objects.equals(calendar.getTimeZone(), expectedCalendarTimeZone)) {
            throw calendarConflictException();
        }
    }

    private String normalizeRequiredText(String value, String blankMessage, int maximumLength, String lengthMessage) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(blankMessage);
        }
        String normalizedValue = value.trim();
        if (normalizedValue.length() > maximumLength) {
            throw new ValidationException(lengthMessage);
        }
        return normalizedValue;
    }

    private List<CalendarEvent> findEvents(Long calendarId, OffsetDateTime rangeStartTime, OffsetDateTime rangeEndTime) {
        StringBuilder queryText = new StringBuilder(
                "select calendarEvent from CalendarEvent calendarEvent "
                        + "where calendarEvent.calendar.id = :calendarId ");
        if (rangeStartTime != null) {
            queryText.append("and calendarEvent.endTime > :rangeStartTime ");
        }
        if (rangeEndTime != null) {
            queryText.append("and calendarEvent.startTime < :rangeEndTime ");
        }
        queryText.append("order by calendarEvent.startTime");

        TypedQuery<CalendarEvent> query = entityManager.createQuery(queryText.toString(), CalendarEvent.class)
                .setParameter("calendarId", calendarId);
        if (rangeStartTime != null) {
            query.setParameter("rangeStartTime", rangeStartTime);
        }
        if (rangeEndTime != null) {
            query.setParameter("rangeEndTime", rangeEndTime);
        }
        return query.getResultList();
    }

    private CalendarEvent requireEvent(Long eventId) {
        CalendarEvent event = entityManager.find(CalendarEvent.class, eventId);
        if (event == null) {
            throw new NotFoundException("Event was not found.");
        }
        return event;
    }

    private void requireExpectedVersion(CalendarEvent event, Integer expectedVersion) {
        if (expectedVersion != null && event.getVersion() != expectedVersion) {
            throw eventConflictException();
        }
    }

    private void flushWithConflictMessage() {
        try {
            entityManager.flush();
        } catch (OptimisticLockException exception) {
            throw eventConflictException();
        }
    }

    private ConflictException eventConflictException() {
        return new ConflictException("This event changed after you opened it. Reload the page and try again.");
    }

    private ConflictException calendarConflictException() {
        return new ConflictException(
                "This calendar changed after you opened the event form. Reload the page and try again.");
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value, int maximumLength, String lengthMessage) {
        String normalizedValue = normalizeOptionalText(value);
        if (normalizedValue != null && normalizedValue.length() > maximumLength) {
            throw new ValidationException(lengthMessage);
        }
        return normalizedValue;
    }

    private record EventTimeRange(OffsetDateTime startTime, OffsetDateTime endTime) {
    }
}
