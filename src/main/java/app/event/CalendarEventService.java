package app.event;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.calendar.CalendarTimeService;
import app.membership.CalendarAccessService;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.OptimisticLockConflicts;
import app.util.TextNormalizer;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Stateless
public class CalendarEventService {
    private static final int MAXIMUM_EVENT_TITLE_LENGTH = 200;
    private static final int MAXIMUM_EVENT_LOCATION_LENGTH = 200;
    private static final String EVENT_CONFLICT_MESSAGE =
            "This event changed after you opened it. Reload the page and try again.";
    private static final String CALENDAR_CONFLICT_MESSAGE =
            "This calendar changed after you opened the event form. Reload the page and try again.";

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarTimeService calendarTimeService;

    public List<CalendarEvent> findPublicEvents(String calendarLinkToken) {
        Calendar calendar = calendarAccessService.requirePublicReadableCalendar(calendarLinkToken);
        return findEvents(calendar.getId());
    }

    public List<CalendarEvent> findEventsForMember(ApplicationUser user, Long calendarId) {
        calendarAccessService.requireCanEdit(user, calendarId);
        return findEvents(calendarId);
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
        Calendar calendar = calendarService.requireCalendarForChildMutation(calendarId);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        requireExpectedCalendarState(calendar, expectedCalendarVersion, expectedCalendarTimeZone);

        EventTimeRange normalizedTimeRange = normalizeEventTimes(calendar, eventTimeInput);
        CalendarEvent event = new CalendarEvent();
        event.setCalendar(calendar);
        applyEventValues(event, title, description, location, eventTimeInput, normalizedTimeRange);
        entityManager.persist(event);
        entityManager.flush();
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
        CalendarEvent event = requireEditableEvent(actingUser, eventId);
        Calendar calendar = calendarService.requireCalendarForChildMutation(event.getCalendar().getId());
        calendarAccessService.requireCanEdit(actingUser, calendar.getId());
        requireExpectedVersion(event, expectedVersion);
        requireExpectedCalendarState(calendar, expectedCalendarVersion, expectedCalendarTimeZone);
        EventTimeRange normalizedTimeRange = normalizeEventTimes(calendar, eventTimeInput);
        applyEventValues(event, title, description, location, eventTimeInput, normalizedTimeRange);
        flushWithConflictMessage();
        return event;
    }

    public void deleteEvent(ApplicationUser actingUser, Long eventId, Integer expectedVersion) {
        CalendarEvent event = requireEditableEvent(actingUser, eventId);
        Calendar calendar = calendarService.requireCalendarForChildMutation(event.getCalendar().getId());
        calendarAccessService.requireCanEdit(actingUser, calendar.getId());
        requireExpectedVersion(event, expectedVersion);
        entityManager.remove(event);
        flushWithConflictMessage();
    }

    private List<CalendarEvent> findEvents(Long calendarId) {
        return List.copyOf(entityManager
                .createQuery(
                        "select calendarEvent from CalendarEvent calendarEvent "
                                + "where calendarEvent.calendar.id = :calendarId "
                                + "order by calendarEvent.startTime, calendarEvent.id",
                        CalendarEvent.class)
                .setParameter("calendarId", calendarId)
                .getResultList());
    }

    private void applyEventValues(
            CalendarEvent event,
            String title,
            String description,
            String location,
            EventTimeInput eventTimeInput,
            EventTimeRange timeRange) {
        event.setTitle(TextNormalizer.normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer."));
        event.setDescription(TextNormalizer.normalizeOptionalMultilineText(
                description,
                TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                "Event description must be 4,000 characters or fewer."));
        event.setLocation(TextNormalizer.normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer."));
        event.setStartTime(timeRange.startTime());
        event.setEndTime(timeRange.endTime());
        event.setAllDay(eventTimeInput instanceof EventTimeInput.AllDay);
    }

    private EventTimeRange normalizeEventTimes(Calendar calendar, EventTimeInput eventTimeInput) {
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

        OffsetDateTime storedStartTime = calendarTimeService.toStoredTime(startTime, calendar.getTimeZone());
        OffsetDateTime storedEndTime = calendarTimeService.toStoredTime(endTime, calendar.getTimeZone());
        if (!storedEndTime.isAfter(storedStartTime)) {
            throw new ValidationException("Event end time must be after the start time.");
        }
        return new EventTimeRange(storedStartTime, storedEndTime);
    }

    private EventTimeRange normalizeAllDayEventDates(
            Calendar calendar,
            LocalDate firstDay,
            LocalDate lastDay) {
        CalendarTimeService.StoredAllDayRange storedRange = calendarTimeService.toStoredAllDayRange(
                firstDay, lastDay, calendar.getTimeZone());
        return new EventTimeRange(storedRange.startTime(), storedRange.endTime());
    }

    private void requireExpectedCalendarState(
            Calendar calendar,
            Integer expectedCalendarVersion,
            String expectedCalendarTimeZone) {
        if (expectedCalendarVersion == null
                || calendar.getVersion() != expectedCalendarVersion.intValue()
                || !Objects.equals(calendar.getTimeZone(), expectedCalendarTimeZone)) {
            throw new ConflictException(CALENDAR_CONFLICT_MESSAGE);
        }
    }

    private CalendarEvent requireEditableEvent(ApplicationUser actingUser, Long eventId) {
        CalendarEvent event = eventId == null ? null : entityManager.find(CalendarEvent.class, eventId);
        if (event == null) {
            throw new NotFoundException("Event was not found.");
        }
        try {
            calendarAccessService.requireCanEdit(actingUser, event.getCalendar().getId());
            return event;
        } catch (AuthorizationException exception) {
            throw new NotFoundException("Event was not found.");
        }
    }

    private void requireExpectedVersion(CalendarEvent event, Integer expectedVersion) {
        OptimisticLockConflicts.requireExpectedVersion(
                event.getVersion(), expectedVersion, EVENT_CONFLICT_MESSAGE);
    }

    private void flushWithConflictMessage() {
        OptimisticLockConflicts.flushOrConflict(entityManager, EVENT_CONFLICT_MESSAGE);
    }

    private record EventTimeRange(OffsetDateTime startTime, OffsetDateTime endTime) {
    }
}
