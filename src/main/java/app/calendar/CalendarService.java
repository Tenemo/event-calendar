package app.calendar;

import app.audit.AuditService;
import app.config.CalendarConfiguration;
import app.event.CalendarEvent;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembership;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.user.ApplicationUser;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Stateless
public class CalendarService {
    private static final int MAXIMUM_CALENDAR_NAME_LENGTH = 160;
    private static final int MAXIMUM_TIME_ZONE_LENGTH = 80;
    private static final int MAXIMUM_LINK_TOKEN_GENERATION_ATTEMPTS = 10;

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private TokenService tokenService;

    @Inject
    private AuditService auditService;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarTimeService calendarTimeService;

    @Inject
    private CalendarConfiguration calendarConfiguration;

    public Calendar createCalendar(ApplicationUser creator, String name) {
        if (creator == null || creator.getId() == null || !creator.isActive()) {
            throw new ValidationException("An active user is required to create a calendar.");
        }

        String normalizedName = normalizeRequiredText(
                name,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer.");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ApplicationUser managedCreator = entityManager.find(ApplicationUser.class, creator.getId());
        if (managedCreator == null || !managedCreator.isActive()) {
            throw new ValidationException("An active user is required to create a calendar.");
        }

        Calendar calendar = new Calendar();
        calendar.setName(normalizedName);
        calendar.setDescription(null);
        calendar.setCalendarLinkToken(generateUniqueCalendarLinkToken());
        calendar.setTimeZone(calendarConfiguration.getDefaultTimeZone());
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        calendar.setCreatedByUser(managedCreator);
        calendar.setCreatedAt(now);
        calendar.setUpdatedAt(now);
        entityManager.persist(calendar);

        CalendarMembership creatorMembership = new CalendarMembership();
        creatorMembership.setCalendar(calendar);
        creatorMembership.setUser(managedCreator);
        creatorMembership.setRole(CalendarRole.ADMIN);
        creatorMembership.setActive(true);
        creatorMembership.setCreatedAt(now);
        creatorMembership.setUpdatedAt(now);
        entityManager.persist(creatorMembership);

        entityManager.flush();
        auditService.record(managedCreator, calendar, "calendar", calendar.getId(), "created", "Calendar created.");
        return calendar;
    }

    public Calendar requireActiveCalendar(Long calendarId) {
        Calendar calendar = entityManager.find(Calendar.class, calendarId);
        if (calendar == null || !calendar.isActive()) {
            throw new NotFoundException("Calendar was not found.");
        }
        return calendar;
    }

    public Calendar requireActiveCalendarForChildMutation(Long calendarId) {
        return requireActiveCalendar(calendarId, LockModeType.PESSIMISTIC_READ);
    }

    public Calendar requireAdminCalendar(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        return requireActiveCalendar(calendarId);
    }

    public List<CalendarMembershipSummary> findCalendarsForUser(ApplicationUser user) {
        if (user == null || user.getId() == null) {
            return List.of();
        }

        return entityManager
                .createQuery(
                        "select new app.calendar.CalendarMembershipSummary("
                                + "calendarMembership.calendar.id, calendarMembership.calendar.name, "
                                + "calendarMembership.calendar.calendarLinkToken, calendarMembership.role, "
                                + "calendarMembership.calendar.publicAccessEnabled) "
                                + "from CalendarMembership calendarMembership "
                                + "where calendarMembership.user.id = :userId "
                                + "and calendarMembership.active = true "
                                + "and calendarMembership.calendar.active = true "
                                + "order by calendarMembership.calendar.name",
                        CalendarMembershipSummary.class)
                .setParameter("userId", user.getId())
                .getResultList();
    }

    public Calendar regenerateCalendarLink(ApplicationUser actingUser, Long calendarId, Integer expectedVersion) {
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        Calendar calendar = requireActiveCalendar(calendarId, LockModeType.PESSIMISTIC_WRITE);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setCalendarLinkToken(generateUniqueCalendarLinkToken());
        calendar.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(
                actingUser,
                calendar,
                "calendar",
                calendar.getId(),
                "public_token_regenerated",
                "Calendar link token regenerated.");
        flushWithConflictMessage();
        return calendar;
    }

    public Calendar updateCalendarSettings(
            ApplicationUser actingUser,
            Long calendarId,
            String name,
            String description,
            String timeZone,
            boolean publicAccessEnabled,
            Integer expectedVersion) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        Calendar calendar = requireActiveCalendar(calendarId, LockModeType.PESSIMISTIC_WRITE);
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setName(normalizeRequiredText(
                name,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer."));
        calendar.setDescription(normalizeOptionalText(description));
        String normalizedTimeZone = normalizeRequiredText(
                timeZone,
                "Time zone is required.",
                MAXIMUM_TIME_ZONE_LENGTH,
                "Time zone must be 80 characters or fewer.");
        String validatedTimeZone = calendarTimeService.normalizeTimeZone(normalizedTimeZone);
        preserveAllDayEventDatesWhenTimeZoneChanges(calendar, validatedTimeZone);
        calendar.setTimeZone(validatedTimeZone);
        calendar.setPublicAccessEnabled(publicAccessEnabled);
        calendar.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actingUser, calendar, "calendar", calendar.getId(), "settings_updated", "Calendar settings updated.");
        flushWithConflictMessage();
        return calendar;
    }

    private void preserveAllDayEventDatesWhenTimeZoneChanges(Calendar calendar, String newTimeZone) {
        String previousTimeZone = calendar.getTimeZone();
        if (newTimeZone.equals(previousTimeZone)) {
            return;
        }

        List<CalendarEvent> allDayEvents = entityManager
                .createQuery(
                        "select calendarEvent from CalendarEvent calendarEvent "
                                + "where calendarEvent.calendar.id = :calendarId "
                                + "and calendarEvent.allDay = true",
                        CalendarEvent.class)
                .setParameter("calendarId", calendar.getId())
                .getResultList();
        for (CalendarEvent allDayEvent : allDayEvents) {
            LocalDate firstDay = calendarTimeService
                    .toCalendarTime(allDayEvent.getStartTime(), previousTimeZone)
                    .toLocalDate();
            LocalDate lastDay = calendarTimeService.toCalendarDateImmediatelyBefore(
                    allDayEvent.getEndTime(),
                    previousTimeZone);
            CalendarTimeService.StoredAllDayRange storedRange =
                    calendarTimeService.toStoredAllDayRange(firstDay, lastDay, newTimeZone);
            allDayEvent.setStartTime(storedRange.startTime());
            allDayEvent.setEndTime(storedRange.endTime());
        }
    }

    private Calendar requireActiveCalendar(Long calendarId, LockModeType lockMode) {
        Calendar calendar = entityManager.find(Calendar.class, calendarId, lockMode);
        if (calendar == null || !calendar.isActive()) {
            throw new NotFoundException("Calendar was not found.");
        }
        return calendar;
    }

    private void requireExpectedVersion(Calendar calendar, Integer expectedVersion) {
        if (expectedVersion != null && calendar.getVersion() != expectedVersion) {
            throw calendarConflictException();
        }
    }

    private void flushWithConflictMessage() {
        try {
            entityManager.flush();
        } catch (OptimisticLockException exception) {
            throw calendarConflictException();
        }
    }

    private ConflictException calendarConflictException() {
        return new ConflictException("This calendar changed after you opened it. Reload the page and try again.");
    }

    private String generateUniqueCalendarLinkToken() {
        for (int attempt = 0; attempt < MAXIMUM_LINK_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String token = tokenService.generateCalendarLinkToken();
            Long existingCount = entityManager
                    .createQuery(
                            "select count(calendarEntity) from Calendar calendarEntity where calendarEntity.calendarLinkToken = :token",
                            Long.class)
                    .setParameter("token", token)
                    .getSingleResult();
            if (existingCount == 0) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique calendar link token.");
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

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
