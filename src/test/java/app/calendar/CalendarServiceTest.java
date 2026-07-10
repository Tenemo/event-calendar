package app.calendar;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.audit.AuditService;
import app.config.CalendarConfiguration;
import app.membership.CalendarAccessService;
import app.membership.CalendarMember;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.AppUser;
import app.util.ValidationException;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarServiceTest {
    @Test
    void calendarCreationGrantsExactlyOneInitialAdminMembershipToTheCreator() {
        AppUser creator = activeUser(42L);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(AppUser.class, creator.getId(), creator)
                .singleResult("count(calendarEntity)", 0L);

        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());
        setField(calendarService, "tokenService", new FixedTokenService("public-token-123456789012345678901234567890"));
        setField(calendarService, "auditService", new NoopAuditService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());
        setField(calendarService, "calendarConfiguration", calendarConfiguration("Europe/Warsaw"));

        Calendar calendar = calendarService.createCalendar(creator, " Kayaking ", " River weekend ");

        Object persistedMembership = entityManagerStub.persistedObjects().stream()
                .filter(CalendarMember.class::isInstance)
                .findFirst()
                .orElseThrow();
        CalendarMember creatorMembership = assertInstanceOf(CalendarMember.class, persistedMembership);

        assertAll(
                () -> assertEquals("Kayaking", calendar.getName()),
                () -> assertEquals("River weekend", calendar.getDescription()),
                () -> assertEquals("public-token-123456789012345678901234567890", calendar.getPublicToken()),
                () -> assertEquals(ZoneOffset.UTC, calendar.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, calendar.getUpdatedAt().getOffset()),
                () -> assertTrue(calendar.isPublicAccessEnabled()),
                () -> assertEquals(CalendarRole.ADMIN, creatorMembership.getRole()),
                () -> assertTrue(creatorMembership.isActive()),
                () -> assertEquals(ZoneOffset.UTC, creatorMembership.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, creatorMembership.getUpdatedAt().getOffset()),
                () -> assertEquals(calendar, creatorMembership.getCalendar()),
                () -> assertEquals(creator, creatorMembership.getUser()),
                () -> assertEquals(1, entityManagerStub.persistedObjects().stream().filter(CalendarMember.class::isInstance).count()),
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
    void updatesValidatedSettingsAndRotatesThePublicLinkWithAuditRecords() {
        AppUser actor = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actor);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(Calendar.class, calendar.getId(), calendar)
                .singleResult("count(calendarEntity)", 0L);
        RecordingAuditService auditService = new RecordingAuditService();
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub.entityManager());
        setField(calendarService, "tokenService", new FixedTokenService("rotated-token-123456789012345678901234567890"));
        setField(calendarService, "auditService", auditService);
        setField(calendarService, "calendarAccessService", new AllowingAccessService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());

        calendarService.updateCalendarSettings(
                actor,
                calendar.getId(),
                " River days ",
                " Summer plans ",
                "Europe/London",
                false,
                calendar.getVersion());
        Calendar rotatedCalendar = calendarService.rotatePublicToken(actor, calendar.getId(), calendar.getVersion());

        assertAll(
                () -> assertEquals("River days", calendar.getName()),
                () -> assertEquals("Summer plans", calendar.getDescription()),
                () -> assertEquals("Europe/London", calendar.getTimezone()),
                () -> assertFalse(calendar.isPublicAccessEnabled()),
                () -> assertEquals(calendar, rotatedCalendar),
                () -> assertEquals("rotated-token-123456789012345678901234567890", rotatedCalendar.getPublicToken()),
                () -> assertEquals(List.of("settings_updated", "public_token_rotated"), auditService.actions),
                () -> assertEquals(2, entityManagerStub.flushCount()));
    }

    @Test
    void rejectsUnknownCalendarTimeZonesBeforeChangingSettings() {
        AppUser actor = activeUser(42L);
        Calendar calendar = activeCalendar(80L, actor);
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "entityManager", entityManagerStub().find(Calendar.class, calendar.getId(), calendar).entityManager());
        setField(calendarService, "calendarAccessService", new AllowingAccessService());
        setField(calendarService, "calendarTimeService", new CalendarTimeService());

        assertThrows(
                ValidationException.class,
                () -> calendarService.updateCalendarSettings(
                        actor,
                        calendar.getId(),
                        calendar.getName(),
                        null,
                        "Unknown/Timezone",
                        true,
                        calendar.getVersion()));
    }

    private static Calendar activeCalendar(Long id, AppUser creator) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setPublicToken("public-token-123456789012345678901234567890");
        calendar.setTimezone("Europe/Warsaw");
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        calendar.setCreatedByUser(creator);
        return calendar;
    }

    private static CalendarConfiguration calendarConfiguration(String defaultTimeZone) {
        CalendarConfiguration calendarConfiguration = new CalendarConfiguration();
        setField(calendarConfiguration, "defaultTimeZone", defaultTimeZone);
        return calendarConfiguration;
    }

    private static AppUser activeUser(Long id) {
        AppUser user = new AppUser();
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
        public String generateToken() {
            return token;
        }
    }

    private static final class NoopAuditService extends AuditService {
        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanAdminister(AppUser user, Long calendarId) {
        }
    }

    private static final class RecordingAuditService extends AuditService {
        private final java.util.List<String> actions = new java.util.ArrayList<>();

        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
            actions.add(action);
        }
    }
}
