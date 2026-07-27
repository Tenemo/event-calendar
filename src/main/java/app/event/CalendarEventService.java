package app.event;

import app.audit.AuditService;
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
    private static final int MAXIMUM_EVENT_PAGE_SIZE = 100;
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

    @Inject
    private AuditService auditService;

    public CalendarEventPage findPublicEvents(
            String calendarLinkToken,
            CalendarEventCursor afterCursor,
            CalendarEventRevision expectedRevision,
            int pageSize) {
        Calendar calendar = calendarAccessService.requirePublicReadableCalendar(calendarLinkToken);
        return findEvents(calendar.getId(), afterCursor, expectedRevision, pageSize);
    }

    public CalendarEventPage findEventsForMember(
            ApplicationUser user,
            Long calendarId,
            CalendarEventCursor afterCursor,
            CalendarEventRevision expectedRevision,
            int pageSize) {
        calendarAccessService.requireCanEdit(user, calendarId);
        return findEvents(calendarId, afterCursor, expectedRevision, pageSize);
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
        String normalizedTitle = TextNormalizer.normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        String normalizedLocation = TextNormalizer.normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer.");
        String normalizedDescription = TextNormalizer.normalizeOptionalMultilineText(
                description,
                TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                "Event description must be 4,000 characters or fewer.");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Calendar calendar = calendarService.requireActiveCalendarForChildMutation(calendarId);
        // Deliberate second check: the acting user's role can be revoked between the first check
        // and the lock, so authorization is confirmed again once the calendar row is held.
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        requireExpectedCalendarState(calendar, expectedCalendarVersion, expectedCalendarTimeZone);
        EventTimeRange normalizedTimeRange = normalizeEventTimes(calendar, eventTimeInput);
        CalendarEvent event = new CalendarEvent();
        event.setCalendar(calendar);
        event.setTitle(normalizedTitle);
        event.setDescription(normalizedDescription);
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
        CalendarEvent event = requireEditableEvent(actingUser, eventId);
        String normalizedTitle = TextNormalizer.normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        String normalizedLocation = TextNormalizer.normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer.");
        String normalizedDescription = TextNormalizer.normalizeOptionalMultilineText(
                description,
                TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                "Event description must be 4,000 characters or fewer.");
        Calendar calendar = calendarService.requireActiveCalendarForChildMutation(event.getCalendar().getId());
        // Deliberate second check: the acting user's role can be revoked between the first check
        // and the lock, so authorization is confirmed again once the calendar row is held.
        calendarAccessService.requireCanEdit(actingUser, calendar.getId());
        requireExpectedVersion(event, expectedVersion);
        requireExpectedCalendarState(calendar, expectedCalendarVersion, expectedCalendarTimeZone);
        EventTimeRange normalizedTimeRange = normalizeEventTimes(calendar, eventTimeInput);

        event.setTitle(normalizedTitle);
        event.setDescription(normalizedDescription);
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
        CalendarEvent event = requireEditableEvent(actingUser, eventId);
        Calendar calendar = calendarService.requireActiveCalendarForChildMutation(event.getCalendar().getId());
        // Deliberate second check: the acting user's role can be revoked between the first check
        // and the lock, so authorization is confirmed again once the calendar row is held.
        calendarAccessService.requireCanEdit(actingUser, calendar.getId());
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
        String timeZone = calendar.getTimeZone();
        CalendarTimeService.StoredAllDayRange storedRange =
                calendarTimeService.toStoredAllDayRange(firstDay, lastDay, timeZone);
        return new EventTimeRange(storedRange.startTime(), storedRange.endTime());
    }

    private void requireExpectedCalendarState(
            Calendar calendar,
            Integer expectedCalendarVersion,
            String expectedCalendarTimeZone) {
        if (expectedCalendarVersion == null
                || calendar.getVersion() != expectedCalendarVersion.intValue()
                || !Objects.equals(calendar.getTimeZone(), expectedCalendarTimeZone)) {
            throw calendarConflictException();
        }
    }

    private CalendarEventPage findEvents(
            Long calendarId,
            CalendarEventCursor afterCursor,
            CalendarEventRevision expectedRevision,
            int pageSize) {
        if (pageSize < 1 || pageSize > MAXIMUM_EVENT_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "The event page size must be between 1 and " + MAXIMUM_EVENT_PAGE_SIZE + ".");
        }

        CalendarEventRevision revisionBeforeQuery = findEventRevision(calendarId);
        if (expectedRevision != null && !expectedRevision.equals(revisionBeforeQuery)) {
            return CalendarEventPage.restartRequired(revisionBeforeQuery);
        }

        String cursorPredicate = afterCursor == null
                ? ""
                : "and calendarEvent.startTime >= :afterStartTime "
                        + "and (calendarEvent.startTime > :afterStartTime "
                        + "or (calendarEvent.startTime = :afterStartTime "
                        + "and calendarEvent.id > :afterEventId)) ";
        TypedQuery<CalendarEvent> eventQuery = entityManager
                .createQuery(
                        "select calendarEvent from CalendarEvent calendarEvent "
                                + "where calendarEvent.calendar.id = :calendarId "
                                + cursorPredicate
                                + "order by calendarEvent.startTime, calendarEvent.id",
                        CalendarEvent.class)
                .setParameter("calendarId", calendarId);
        if (afterCursor != null) {
            eventQuery
                    .setParameter("afterStartTime", afterCursor.startTime())
                    .setParameter("afterEventId", afterCursor.eventId());
        }
        List<CalendarEvent> loadedEvents = eventQuery
                .setMaxResults(pageSize + 1)
                .getResultList();
        CalendarEventRevision revisionAfterQuery = findEventRevision(calendarId);
        if (!revisionBeforeQuery.equals(revisionAfterQuery)) {
            return CalendarEventPage.restartRequired(revisionAfterQuery);
        }
        boolean hasMore = loadedEvents.size() > pageSize;
        List<CalendarEvent> pageEvents = hasMore
                ? List.copyOf(loadedEvents.subList(0, pageSize))
                : List.copyOf(loadedEvents);
        CalendarEventCursor nextCursor = pageEvents.isEmpty()
                ? null
                : CalendarEventCursor.after(pageEvents.getLast());
        return new CalendarEventPage(
                pageEvents,
                hasMore,
                nextCursor,
                revisionAfterQuery,
                false);
    }

    CalendarEventRevision findEventRevision(Long calendarId) {
        // This reads one constant-size aggregate instead of retaining an unbounded identifier
        // snapshot. It scans one calendar's indexed event rows twice per explicit page request,
        // which is a deliberate v1 trade-off for friend-group calendars; a stored counter would
        // require schema state and would serialize otherwise independent event mutations.
        Object[] eventRevisionValues = entityManager
                .createQuery(
                        "select count(calendarEvent), max(calendarEvent.id), "
                                + "sum(calendarEvent.version) "
                                + "from CalendarEvent calendarEvent "
                                + "where calendarEvent.calendar.id = :calendarId",
                        Object[].class)
                .setParameter("calendarId", calendarId)
                .getSingleResult();
        long numberOfEvents = ((Number) eventRevisionValues[0]).longValue();
        long maximumEventId = eventRevisionValues[1] == null
                ? 0L
                : ((Number) eventRevisionValues[1]).longValue();
        long accumulatedEventVersions = eventRevisionValues[2] == null
                ? 0L
                : ((Number) eventRevisionValues[2]).longValue();
        return new CalendarEventRevision(
                numberOfEvents,
                maximumEventId,
                accumulatedEventVersions);
    }

    private CalendarEvent requireEvent(Long eventId) {
        CalendarEvent event = entityManager.find(CalendarEvent.class, eventId);
        if (event == null) {
            throw new NotFoundException("Event was not found.");
        }
        return event;
    }

    private CalendarEvent requireEditableEvent(ApplicationUser actingUser, Long eventId) {
        CalendarEvent event = requireEvent(eventId);
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

    private ConflictException calendarConflictException() {
        return new ConflictException(CALENDAR_CONFLICT_MESSAGE);
    }

    private record EventTimeRange(OffsetDateTime startTime, OffsetDateTime endTime) {
    }
}
