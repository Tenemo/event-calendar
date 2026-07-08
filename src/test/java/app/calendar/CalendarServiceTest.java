package app.calendar;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.audit.AuditService;
import app.membership.CalendarMember;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.AppUser;
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
                () -> assertTrue(calendar.isPublicAccessEnabled()),
                () -> assertEquals(CalendarRole.ADMIN, creatorMembership.getRole()),
                () -> assertTrue(creatorMembership.isActive()),
                () -> assertEquals(calendar, creatorMembership.getCalendar()),
                () -> assertEquals(creator, creatorMembership.getUser()),
                () -> assertEquals(1, entityManagerStub.persistedObjects().stream().filter(CalendarMember.class::isInstance).count()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
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
}
