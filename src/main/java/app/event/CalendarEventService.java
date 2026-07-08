package app.event;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.user.AppUser;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

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
    private AuditService auditService;

    public List<CalendarEvent> findPublicEvents(String publicToken, OffsetDateTime rangeStart, OffsetDateTime rangeEnd) {
        Calendar calendar = calendarAccessService.requirePublicReadableCalendar(publicToken);
        return findEvents(calendar.getId(), rangeStart, rangeEnd);
    }

    public List<CalendarEvent> findMemberEvents(AppUser user, Long calendarId, OffsetDateTime rangeStart, OffsetDateTime rangeEnd) {
        calendarAccessService.requireCanView(user, calendarId);
        return findEvents(calendarId, rangeStart, rangeEnd);
    }

    public CalendarEvent createEvent(
            AppUser actor,
            Long calendarId,
            String title,
            String description,
            String location,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            boolean allDay) {
        calendarAccessService.requireCanEdit(actor, calendarId);
        String normalizedTitle = normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        String normalizedLocation = normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer.");
        validateEventTimes(startAt, endAt);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Calendar calendar = calendarService.requireActiveCalendar(calendarId);
        CalendarEvent event = new CalendarEvent();
        event.setCalendar(calendar);
        event.setTitle(normalizedTitle);
        event.setDescription(normalizeOptionalText(description));
        event.setLocation(normalizedLocation);
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setAllDay(allDay);
        event.setCreatedByUser(actor);
        event.setUpdatedByUser(actor);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        entityManager.persist(event);
        entityManager.flush();
        auditService.record(actor, calendar, "calendar_event", event.getId(), "created", "Event created.");
        return event;
    }

    public CalendarEvent updateEvent(
            AppUser actor,
            Long eventId,
            String title,
            String description,
            String location,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            boolean allDay) {
        CalendarEvent event = requireEvent(eventId);
        calendarAccessService.requireCanEdit(actor, event.getCalendar().getId());
        String normalizedTitle = normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        String normalizedLocation = normalizeOptionalText(
                location,
                MAXIMUM_EVENT_LOCATION_LENGTH,
                "Event location must be 200 characters or fewer.");
        validateEventTimes(startAt, endAt);

        event.setTitle(normalizedTitle);
        event.setDescription(normalizeOptionalText(description));
        event.setLocation(normalizedLocation);
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setAllDay(allDay);
        event.setUpdatedByUser(actor);
        event.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actor, event.getCalendar(), "calendar_event", event.getId(), "updated", "Event updated.");
        return event;
    }

    public void deleteEvent(AppUser actor, Long eventId) {
        CalendarEvent event = requireEvent(eventId);
        calendarAccessService.requireCanEdit(actor, event.getCalendar().getId());
        auditService.record(actor, event.getCalendar(), "calendar_event", event.getId(), "deleted", "Event deleted.");
        entityManager.remove(event);
    }

    public void validateEvent(String title, OffsetDateTime startAt, OffsetDateTime endAt) {
        normalizeRequiredText(
                title,
                "Event title is required.",
                MAXIMUM_EVENT_TITLE_LENGTH,
                "Event title must be 200 characters or fewer.");
        validateEventTimes(startAt, endAt);
    }

    private void validateEventTimes(OffsetDateTime startAt, OffsetDateTime endAt) {
        if (startAt == null) {
            throw new ValidationException("Event start time is required.");
        }
        if (endAt == null) {
            throw new ValidationException("Event end time is required.");
        }
        if (!endAt.isAfter(startAt)) {
            throw new ValidationException("Event end time must be after the start time.");
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

    private List<CalendarEvent> findEvents(Long calendarId, OffsetDateTime rangeStart, OffsetDateTime rangeEnd) {
        StringBuilder queryText = new StringBuilder(
                "select calendarEvent from CalendarEvent calendarEvent "
                        + "where calendarEvent.calendar.id = :calendarId ");
        if (rangeStart != null) {
            queryText.append("and calendarEvent.endAt > :rangeStart ");
        }
        if (rangeEnd != null) {
            queryText.append("and calendarEvent.startAt < :rangeEnd ");
        }
        queryText.append("order by calendarEvent.startAt");

        TypedQuery<CalendarEvent> query = entityManager.createQuery(queryText.toString(), CalendarEvent.class)
                .setParameter("calendarId", calendarId);
        if (rangeStart != null) {
            query.setParameter("rangeStart", rangeStart);
        }
        if (rangeEnd != null) {
            query.setParameter("rangeEnd", rangeEnd);
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
}
