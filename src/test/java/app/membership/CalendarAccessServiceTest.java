package app.membership;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.AppUser;
import app.util.AuthorizationException;
import org.junit.jupiter.api.Test;

final class CalendarAccessServiceTest {
    @Test
    void viewerCanViewButCannotEditOrAdminister() {
        CalendarAccessService accessService = accessServiceReturningRole(CalendarRole.VIEWER);
        AppUser user = activeUser();

        assertAll(
                () -> assertDoesNotThrow(() -> accessService.requireCanView(user, 10L)),
                () -> assertThrows(AuthorizationException.class, () -> accessService.requireCanEdit(user, 10L)),
                () -> assertThrows(AuthorizationException.class, () -> accessService.requireCanAdminister(user, 10L)));
    }

    @Test
    void editorCanEditButCannotAdminister() {
        CalendarAccessService accessService = accessServiceReturningRole(CalendarRole.EDITOR);
        AppUser user = activeUser();

        assertAll(
                () -> assertDoesNotThrow(() -> accessService.requireCanView(user, 10L)),
                () -> assertDoesNotThrow(() -> accessService.requireCanEdit(user, 10L)),
                () -> assertThrows(AuthorizationException.class, () -> accessService.requireCanAdminister(user, 10L)));
    }

    @Test
    void adminCanViewEditAndAdminister() {
        CalendarAccessService accessService = accessServiceReturningRole(CalendarRole.ADMIN);
        AppUser user = activeUser();

        assertAll(
                () -> assertDoesNotThrow(() -> accessService.requireCanView(user, 10L)),
                () -> assertDoesNotThrow(() -> accessService.requireCanEdit(user, 10L)),
                () -> assertDoesNotThrow(() -> accessService.requireCanAdminister(user, 10L)));
    }

    @Test
    void missingMembershipRejectsMemberAccess() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from CalendarMember");
        CalendarAccessService accessService = new CalendarAccessService();
        setField(accessService, "entityManager", entityManagerStub.entityManager());
        setField(accessService, "calendarRolePolicy", new CalendarRolePolicy());

        assertThrows(AuthorizationException.class, () -> accessService.requireCanView(activeUser(), 10L));
    }

    private static CalendarAccessService accessServiceReturningRole(CalendarRole role) {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("select calendarMember.role", role);
        CalendarAccessService accessService = new CalendarAccessService();
        setField(accessService, "entityManager", entityManagerStub.entityManager());
        setField(accessService, "calendarRolePolicy", new CalendarRolePolicy());
        return accessService;
    }

    private static AppUser activeUser() {
        AppUser user = new AppUser();
        setEntityId(user, 20L);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }
}
