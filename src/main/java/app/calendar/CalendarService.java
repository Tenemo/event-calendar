package app.calendar;

import app.audit.AuditService;
import app.config.NewCalendarDefaults;
import app.event.CalendarEvent;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembership;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.user.ApplicationUser;
import app.util.NotFoundException;
import app.util.OptimisticLockConflicts;
import app.util.TextNormalizer;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Stateless
public class CalendarService {
    private static final int MAXIMUM_CALENDAR_NAME_LENGTH = 160;
    private static final int MAXIMUM_TIME_ZONE_LENGTH = 80;
    private static final int MAXIMUM_ALL_DAY_EVENTS_PER_TIME_ZONE_CHANGE = 1_000;
    private static final String CALENDAR_CONFLICT_MESSAGE =
            "This calendar changed after you opened it. Reload the page and try again.";

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
    private NewCalendarDefaults newCalendarDefaults;

    public Calendar createCalendar(ApplicationUser creator, String name) {
        if (creator == null || creator.getId() == null || !creator.isActive()) {
            throw new ValidationException("An active user is required to create a calendar.");
        }

        String normalizedName = normalizeAndValidateCalendarName(name);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ApplicationUser managedCreator = entityManager.find(ApplicationUser.class, creator.getId());
        if (managedCreator == null || !managedCreator.isActive()) {
            throw new ValidationException("An active user is required to create a calendar.");
        }

        Calendar calendar = new Calendar();
        calendar.setName(normalizedName);
        calendar.setDescription(null);
        calendar.setCalendarLinkToken(generateCalendarLinkToken());
        calendar.setTimeZone(newCalendarDefaults.getDefaultTimeZone());
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

    public String normalizeAndValidateCalendarName(String calendarName) {
        return TextNormalizer.normalizeRequiredText(
                calendarName,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer.");
    }

    private Calendar requireActiveCalendar(Long calendarId) {
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
        // Deliberate second check: the acting user's role can be revoked between the first check and
        // the lock, so authorization is confirmed again once the calendar row is held.
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setCalendarLinkToken(generateCalendarLinkToken());
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
        // Deliberate second check: the acting user's role can be revoked between the first check and
        // the lock, so authorization is confirmed again once the calendar row is held.
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setName(TextNormalizer.normalizeRequiredText(
                name,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer."));
        calendar.setDescription(TextNormalizer.normalizeOptionalMultilineText(
                description,
                TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                "Calendar description must be 4,000 characters or fewer."));
        String normalizedTimeZone = TextNormalizer.normalizeRequiredText(
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

        long numberOfAllDayEvents = entityManager
                .createQuery(
                        "select count(calendarEvent) from CalendarEvent calendarEvent "
                                + "where calendarEvent.calendar.id = :calendarId "
                                + "and calendarEvent.allDay = true",
                        Long.class)
                .setParameter("calendarId", calendar.getId())
                .getSingleResult();
        if (numberOfAllDayEvents > MAXIMUM_ALL_DAY_EVENTS_PER_TIME_ZONE_CHANGE) {
            throw new ValidationException(
                    "The calendar time zone cannot be changed while it has more than "
                            + MAXIMUM_ALL_DAY_EVENTS_PER_TIME_ZONE_CHANGE
                            + " all-day events.");
        }

        List<CalendarEvent> allDayEvents = entityManager
                .createQuery(
                        "select calendarEvent from CalendarEvent calendarEvent "
                                + "where calendarEvent.calendar.id = :calendarId "
                                + "and calendarEvent.allDay = true",
                        CalendarEvent.class)
                .setParameter("calendarId", calendar.getId())
                .setMaxResults(MAXIMUM_ALL_DAY_EVENTS_PER_TIME_ZONE_CHANGE)
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
        if (calendar == null) {
            throw new NotFoundException("Calendar was not found.");
        }
        entityManager.refresh(calendar);
        if (!calendar.isActive()) {
            throw new NotFoundException("Calendar was not found.");
        }
        return calendar;
    }

    private void requireExpectedVersion(Calendar calendar, Integer expectedVersion) {
        OptimisticLockConflicts.requireExpectedVersion(
                calendar.getVersion(), expectedVersion, CALENDAR_CONFLICT_MESSAGE);
    }

    private void flushWithConflictMessage() {
        OptimisticLockConflicts.flushOrConflict(entityManager, CALENDAR_CONFLICT_MESSAGE);
    }

    /**
     * Uniqueness is guaranteed by the unique {@code calendar.public_token} constraint, not by
     * reading the column first: a read cannot see a token another transaction is about to insert,
     * so a pre-check would still leave the constraint as the only real guarantee.
     */
    private String generateCalendarLinkToken() {
        return tokenService.generateCalendarLinkToken();
    }

}
