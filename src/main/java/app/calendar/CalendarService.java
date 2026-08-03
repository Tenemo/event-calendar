package app.calendar;

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
import java.util.List;

@Stateless
public class CalendarService {
    private static final int MAXIMUM_CALENDAR_NAME_LENGTH = 160;
    private static final int MAXIMUM_TIME_ZONE_LENGTH = 80;
    private static final String CALENDAR_CONFLICT_MESSAGE =
            "This calendar changed after you opened it. Reload the page and try again.";

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private TokenService tokenService;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarTimeService calendarTimeService;

    @Inject
    private NewCalendarDefaults newCalendarDefaults;

    public Calendar createCalendar(ApplicationUser creator, String name) {
        if (creator == null || creator.getId() == null) {
            throw new ValidationException("A registered user is required to create a calendar.");
        }

        ApplicationUser managedCreator = entityManager.find(ApplicationUser.class, creator.getId());
        if (managedCreator == null) {
            throw new ValidationException("A registered user is required to create a calendar.");
        }

        Calendar calendar = new Calendar();
        calendar.setName(normalizeAndValidateCalendarName(name));
        calendar.setCalendarLinkToken(tokenService.generateCalendarLinkToken());
        calendar.setTimeZone(newCalendarDefaults.getDefaultTimeZone());
        calendar.setPublicAccessEnabled(true);
        entityManager.persist(calendar);

        CalendarMembership creatorMembership = new CalendarMembership();
        creatorMembership.setCalendar(calendar);
        creatorMembership.setUser(managedCreator);
        creatorMembership.setRole(CalendarRole.ADMIN);
        entityManager.persist(creatorMembership);
        entityManager.flush();
        return calendar;
    }

    public String normalizeAndValidateCalendarName(String calendarName) {
        return TextNormalizer.normalizeRequiredText(
                calendarName,
                "Calendar name is required.",
                MAXIMUM_CALENDAR_NAME_LENGTH,
                "Calendar name must be 160 characters or fewer.");
    }

    public Calendar requireCalendarForChildMutation(Long calendarId) {
        return requireCalendar(calendarId, LockModeType.PESSIMISTIC_READ);
    }

    public Calendar requireAdminCalendar(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        return requireCalendar(calendarId, LockModeType.NONE);
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
                                + "order by calendarMembership.calendar.name",
                        CalendarMembershipSummary.class)
                .setParameter("userId", user.getId())
                .getResultList();
    }

    public Calendar regenerateCalendarLink(
            ApplicationUser actingUser,
            Long calendarId,
            Integer expectedVersion) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        Calendar calendar = requireCalendar(calendarId, LockModeType.PESSIMISTIC_WRITE);
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        requireExpectedVersion(calendar, expectedVersion);
        calendar.setCalendarLinkToken(tokenService.generateCalendarLinkToken());
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
        Calendar calendar = requireCalendar(calendarId, LockModeType.PESSIMISTIC_WRITE);
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        requireExpectedVersion(calendar, expectedVersion);

        calendar.setName(normalizeAndValidateCalendarName(name));
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
                    allDayEvent.getEndTime(), previousTimeZone);
            CalendarTimeService.StoredAllDayRange storedRange =
                    calendarTimeService.toStoredAllDayRange(firstDay, lastDay, newTimeZone);
            allDayEvent.setStartTime(storedRange.startTime());
            allDayEvent.setEndTime(storedRange.endTime());
        }
    }

    private Calendar requireCalendar(Long calendarId, LockModeType lockMode) {
        if (calendarId == null) {
            throw new NotFoundException("Calendar was not found.");
        }
        Calendar calendar = lockMode == LockModeType.NONE
                ? entityManager.find(Calendar.class, calendarId)
                : entityManager.find(Calendar.class, calendarId, lockMode);
        if (calendar == null) {
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
}
