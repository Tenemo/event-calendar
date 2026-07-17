package app.calendar;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.audit.AuditService;
import app.config.CalendarConfiguration;
import app.event.CalendarEvent;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembership;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.testsupport.ServiceTestSupport.FindLock;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.ValidationException;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarServiceTest {
    @Test
    void calendarCreationGrantsExactlyOneInitialAdminMembershipToTheCreator() {
        ApplicationUser creator = activeUser(42L);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(ApplicationUser.class, creator.getId(), creator)
                .singleResult("count(calendarEntity)", 0L);

        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());
        setField(calendarService, "tokenService", new FixedTokenService("Abc_123-xY0"));
        setField(calendarService, "auditService", new NoOperationAuditService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());
        setField(calendarService, "calendarConfiguration", calendarConfiguration("Europe/Warsaw"));

        Calendar calendar = calendarService.createCalendar(creator, " Kayaking ");

        Object persistedMembership = entityManagerStub.persistedObjects().stream()
                .filter(CalendarMembership.class::isInstance)
                .findFirst()
                .orElseThrow();
        CalendarMembership creatorMembership = assertInstanceOf(CalendarMembership.class, persistedMembership);

        assertAll(
                () -> assertEquals("Kayaking", calendar.getName()),
                () -> assertNull(calendar.getDescription()),
                () -> assertEquals("Abc_123-xY0", calendar.getCalendarLinkToken()),
                () -> assertEquals(ZoneOffset.UTC, calendar.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, calendar.getUpdatedAt().getOffset()),
                () -> assertTrue(calendar.isPublicAccessEnabled()),
                () -> assertEquals(CalendarRole.ADMIN, creatorMembership.getRole()),
                () -> assertTrue(creatorMembership.isActive()),
                () -> assertEquals(ZoneOffset.UTC, creatorMembership.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, creatorMembership.getUpdatedAt().getOffset()),
                () -> assertEquals(calendar, creatorMembership.getCalendar()),
                () -> assertEquals(creator, creatorMembership.getUser()),
                () -> assertEquals(1, entityManagerStub.persistedObjects().stream().filter(CalendarMembership.class::isInstance).count()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }

    @Test
    void rejectsCalendarNamesLongerThanTheSchemaAllowsBeforePersistence() {
        CalendarService calendarService = new CalendarService();

        assertThrows(
                ValidationException.class,
                () -> calendarService.createCalendar(activeUser(42L), "K".repeat(161)));
    }

    @Test
    void updatesValidatedSettingsAndRegeneratesTheCalendarLinkWithAuditRecords() {
        ApplicationUser actingUser = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actingUser);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(Calendar.class, calendar.getId(), calendar)
                .resultList("calendarEvent.allDay = true", List.of())
                .singleResult("count(calendarEntity)", 0L);
        RecordingAuditService auditService = new RecordingAuditService();
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());
        setField(calendarService, "tokenService", new FixedTokenService("New_123-xY0"));
        setField(calendarService, "auditService", auditService);
        setField(calendarService, "calendarAccessService", new AllowingAccessService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());

        calendarService.updateCalendarSettings(
                actingUser,
                calendar.getId(),
                " River days ",
                " Summer plans ",
                "Europe/London",
                false,
                calendar.getVersion());
        Calendar regeneratedCalendar = calendarService.regenerateCalendarLink(
                actingUser, calendar.getId(), calendar.getVersion());

        assertAll(
                () -> assertEquals("River days", calendar.getName()),
                () -> assertEquals("Summer plans", calendar.getDescription()),
                () -> assertEquals("Europe/London", calendar.getTimeZone()),
                () -> assertFalse(calendar.isPublicAccessEnabled()),
                () -> assertEquals(calendar, regeneratedCalendar),
                () -> assertEquals(
                        "New_123-xY0",
                        regeneratedCalendar.getCalendarLinkToken()),
                () -> assertEquals(List.of("settings_updated", "public_token_regenerated"), auditService.actions),
                () -> assertEquals(
                        List.of(
                                new FindLock(Calendar.class, calendar.getId(), LockModeType.PESSIMISTIC_WRITE),
                                new FindLock(Calendar.class, calendar.getId(), LockModeType.PESSIMISTIC_WRITE)),
                        entityManagerStub.findLocks()),
                () -> assertEquals(2, entityManagerStub.flushCount()));
    }

    @Test
    void eventMutationsHoldASharedCalendarLockWhileInterpretingCivilTimes() {
        ApplicationUser creator = activeUser(42L);
        Calendar calendar = activeCalendar(80L, creator);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(Calendar.class, calendar.getId(), calendar);
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());

        Calendar loadedCalendar = calendarService.requireActiveCalendarForChildMutation(calendar.getId());

        assertAll(
                () -> assertEquals(calendar, loadedCalendar),
                () -> assertEquals(
                        List.of(new FindLock(
                                Calendar.class,
                                calendar.getId(),
                                LockModeType.PESSIMISTIC_READ)),
                        entityManagerStub.findLocks()));
    }

    @Test
    void changingTimeZonePreservesAllDayCivilDatesWithoutChangingTimedInstants() {
        ApplicationUser actingUser = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actingUser);
        CalendarEvent allDayEvent = calendarEvent(
                calendar,
                true,
                "2026-07-22T00:00:00+02:00",
                "2026-07-25T00:00:00+02:00");
        CalendarEvent timedEvent = calendarEvent(
                calendar,
                false,
                "2026-07-20T10:00:00+02:00",
                "2026-07-20T12:00:00+02:00");
        OffsetDateTime originalTimedStart = timedEvent.getStartTime();
        OffsetDateTime originalTimedEnd = timedEvent.getEndTime();
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(Calendar.class, calendar.getId(), calendar)
                .resultList("calendarEvent.allDay = true", List.of(allDayEvent));
        CalendarService calendarService = configuredSettingsService(entityManagerStub);

        calendarService.updateCalendarSettings(
                actingUser,
                calendar.getId(),
                calendar.getName(),
                calendar.getDescription(),
                "America/New_York",
                calendar.isPublicAccessEnabled(),
                calendar.getVersion());

        assertAll(
                () -> assertEquals("America/New_York", calendar.getTimeZone()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-22T00:00:00-04:00"),
                        allDayEvent.getStartTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-25T00:00:00-04:00"),
                        allDayEvent.getEndTime()),
                () -> assertEquals(originalTimedStart, timedEvent.getStartTime()),
                () -> assertEquals(originalTimedEnd, timedEvent.getEndTime()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }

    @Test
    void changingTimeZonePreservesAnAllDayEventBeforeASkippedCivilDate() {
        ApplicationUser actingUser = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actingUser);
        calendar.setTimeZone("Pacific/Apia");
        CalendarEvent allDayEvent = calendarEvent(
                calendar,
                true,
                "2011-12-29T00:00:00-10:00",
                "2011-12-31T00:00:00+14:00");
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(Calendar.class, calendar.getId(), calendar)
                .resultList("calendarEvent.allDay = true", List.of(allDayEvent));
        CalendarService calendarService = configuredSettingsService(entityManagerStub);

        calendarService.updateCalendarSettings(
                actingUser,
                calendar.getId(),
                calendar.getName(),
                calendar.getDescription(),
                "Europe/Warsaw",
                calendar.isPublicAccessEnabled(),
                calendar.getVersion());

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2011-12-29T00:00:00+01:00"),
                        allDayEvent.getStartTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2011-12-30T00:00:00+01:00"),
                        allDayEvent.getEndTime()));
    }

    @Test
    void rejectsUnknownCalendarTimeZonesBeforeChangingSettings() {
        ApplicationUser actingUser = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actingUser);
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub().find(Calendar.class, calendar.getId(), calendar).entityManager());
        setField(calendarService, "calendarAccessService", new AllowingAccessService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());

        assertThrows(
                ValidationException.class,
                () -> calendarService.updateCalendarSettings(
                        actingUser,
                        calendar.getId(),
                        calendar.getName(),
                        null,
                        "Unknown/TimeZone",
                        true,
                        calendar.getVersion()));
    }

    @Test
    void settingsRequireAdminAndLinkRegenerationRequiresEditorAccessBeforeLoadingTheCalendar() {
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "calendarAccessService", new RejectingAccessService());

        assertAll(
                () -> assertThrows(
                        AuthorizationException.class,
                        () -> calendarService.updateCalendarSettings(
                                activeUser(42L), 80L, "Changed", null, "Europe/Warsaw", true, 0)),
                () -> assertThrows(
                        AuthorizationException.class,
                        () -> calendarService.regenerateCalendarLink(activeUser(42L), 80L, 0)));
    }

    @Test
    void roleDependentCalendarMutationsRecheckPermissionAfterTakingTheCalendarLock() {
        ApplicationUser actingUser = activeUser(42L);
        Calendar settingsCalendar = activeCalendar(80L, actingUser);
        CalendarService settingsService = configuredSettingsService(
                entityManagerStub().find(Calendar.class, settingsCalendar.getId(), settingsCalendar));
        setField(settingsService, "calendarAccessService", new AdministrationRevokedAfterInitialCheckAccessService());

        AuthorizationException settingsException = assertThrows(
                AuthorizationException.class,
                () -> settingsService.updateCalendarSettings(
                        actingUser,
                        settingsCalendar.getId(),
                        "Changed",
                        null,
                        "Europe/London",
                        false,
                        settingsCalendar.getVersion()));

        Calendar tokenCalendar = activeCalendar(81L, actingUser);
        CalendarService tokenService = configuredSettingsService(
                entityManagerStub().find(Calendar.class, tokenCalendar.getId(), tokenCalendar));
        setField(tokenService, "calendarAccessService", new EditAccessRevokedAfterInitialCheckAccessService());

        AuthorizationException tokenException = assertThrows(
                AuthorizationException.class,
                () -> tokenService.regenerateCalendarLink(
                        actingUser,
                        tokenCalendar.getId(),
                        tokenCalendar.getVersion()));

        assertAll(
                () -> assertEquals("Admin access is required.", settingsException.getMessage()),
                () -> assertEquals("Editor access is required.", tokenException.getMessage()),
                () -> assertEquals("Kayaking", settingsCalendar.getName()),
                () -> assertEquals("Europe/Warsaw", settingsCalendar.getTimeZone()),
                () -> assertTrue(settingsCalendar.isPublicAccessEnabled()),
                () -> assertEquals(
                        "Abc_123-xY0",
                        tokenCalendar.getCalendarLinkToken()));
    }

    @Test
    void staleCalendarVersionsRejectSettingsAndLinkRegenerationWithoutMutation() {
        ApplicationUser actingUser = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actingUser);
        EntityManagerStub entityManagerStub = entityManagerStub().find(Calendar.class, calendar.getId(), calendar);
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());
        setField(calendarService, "calendarAccessService", new AllowingAccessService());

        assertAll(
                () -> assertThrows(
                        ConflictException.class,
                        () -> calendarService.updateCalendarSettings(
                                actingUser,
                                calendar.getId(),
                                "Changed",
                                null,
                                "Europe/Warsaw",
                                false,
                                calendar.getVersion() + 1)),
                () -> assertThrows(
                        ConflictException.class,
                        () -> calendarService.regenerateCalendarLink(
                                actingUser, calendar.getId(), calendar.getVersion() + 1)),
                () -> assertEquals("Kayaking", calendar.getName()),
                () -> assertTrue(calendar.isPublicAccessEnabled()),
                () -> assertEquals("Abc_123-xY0", calendar.getCalendarLinkToken()),
                () -> assertEquals(0, entityManagerStub.flushCount()));
    }

    private static Calendar activeCalendar(Long id, ApplicationUser creator) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setCalendarLinkToken("Abc_123-xY0");
        calendar.setTimeZone("Europe/Warsaw");
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        calendar.setCreatedByUser(creator);
        return calendar;
    }

    private static CalendarEvent calendarEvent(
            Calendar calendar,
            boolean allDay,
            String startTime,
            String endTime) {
        CalendarEvent event = new CalendarEvent();
        event.setCalendar(calendar);
        event.setTitle(allDay ? "River weekend" : "River launch");
        event.setStartTime(OffsetDateTime.parse(startTime));
        event.setEndTime(OffsetDateTime.parse(endTime));
        event.setAllDay(allDay);
        return event;
    }

    private static CalendarService configuredSettingsService(EntityManagerStub entityManagerStub) {
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());
        setField(calendarService, "auditService", new NoOperationAuditService());
        setField(calendarService, "calendarAccessService", new AllowingAccessService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());
        return calendarService;
    }

    private static CalendarConfiguration calendarConfiguration(String defaultTimeZone) {
        CalendarConfiguration calendarConfiguration = new CalendarConfiguration();
        setField(calendarConfiguration, "defaultTimeZone", defaultTimeZone);
        return calendarConfiguration;
    }

    private static ApplicationUser activeUser(Long id) {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, id);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }

    private static final class FixedTokenService extends TokenService {
        private final String token;

        private FixedTokenService(String token) {
            this.token = token;
        }

        @Override
        public String generateCalendarLinkToken() {
            return token;
        }
    }

    private static final class NoOperationAuditService extends AuditService {
        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
        }

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
        }
    }

    private static final class RejectingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            throw new AuthorizationException("Editor access is required.");
        }

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
            throw new AuthorizationException("Admin access is required.");
        }
    }

    private static final class AdministrationRevokedAfterInitialCheckAccessService extends CalendarAccessService {
        private int administrationChecks;

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
            administrationChecks++;
            if (administrationChecks > 1) {
                throw new AuthorizationException("Admin access is required.");
            }
        }
    }

    private static final class EditAccessRevokedAfterInitialCheckAccessService extends CalendarAccessService {
        private int editChecks;

        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            editChecks++;
            if (editChecks > 1) {
                throw new AuthorizationException("Editor access is required.");
            }
        }
    }

    private static final class RecordingAuditService extends AuditService {
        private final java.util.List<String> actions = new java.util.ArrayList<>();

        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
            actions.add(action);
        }
    }
}
