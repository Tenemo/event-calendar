package app.membership;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.calendar.Calendar;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import org.junit.jupiter.api.Test;

final class CalendarAccessServiceTest {
    @Test
    void editorCanEditButCannotAdminister() {
        CalendarAccessService accessService = accessServiceReturningRole(CalendarRole.EDITOR);
        ApplicationUser user = activeUser();

        assertAll(
                () -> assertDoesNotThrow(() -> accessService.requireCanEdit(user, 10L)),
                () -> assertThrows(AuthorizationException.class, () -> accessService.requireCanAdminister(user, 10L)));
    }

    @Test
    void adminCanEditAndAdminister() {
        CalendarAccessService accessService = accessServiceReturningRole(CalendarRole.ADMIN);
        ApplicationUser user = activeUser();

        assertAll(
                () -> assertDoesNotThrow(() -> accessService.requireCanEdit(user, 10L)),
                () -> assertDoesNotThrow(() -> accessService.requireCanAdminister(user, 10L)));
    }

    @Test
    void missingMembershipRejectsEditorAccess() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from CalendarMembership");
        CalendarAccessService accessService = accessService(entityManagerStub);

        assertThrows(AuthorizationException.class, () -> accessService.requireCanEdit(activeUser(), 10L));
    }

    @Test
    void enabledCalendarTokenAllowsAnonymousReadOnlyAccess() {
        Calendar calendar = activeCalendar(true);
        CalendarAccessService accessService = accessService(
                entityManagerStub().singleResult("from Calendar calendarEntity", calendar));

        assertEquals(calendar, accessService.requireCalendarReadableByLinkToken(null, calendar.getCalendarLinkToken()));
        assertEquals(calendar, accessService.requirePublicReadableCalendar(calendar.getCalendarLinkToken()));
    }

    @Test
    void disabledCalendarTokenAllowsMembersButRejectsAnonymousAndUnrelatedUsers() {
        Calendar calendar = activeCalendar(false);
        CalendarAccessService memberAccessService = accessService(entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .singleResult("select calendarMembership.role", CalendarRole.EDITOR));
        CalendarAccessService anonymousAccessService = accessService(
                entityManagerStub().singleResult("from Calendar calendarEntity", calendar));
        CalendarAccessService unrelatedAccessService = accessService(entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .singleResultNotFound("select calendarMembership.role"));

        assertAll(
                () -> assertEquals(
                        calendar,
                        memberAccessService.requireCalendarReadableByLinkToken(activeUser(), calendar.getCalendarLinkToken())),
                () -> assertThrows(
                        NotFoundException.class,
                        () -> anonymousAccessService.requireCalendarReadableByLinkToken(null, calendar.getCalendarLinkToken())),
                () -> assertThrows(
                        NotFoundException.class,
                        () -> unrelatedAccessService.requireCalendarReadableByLinkToken(
                                activeUser(), calendar.getCalendarLinkToken())),
                () -> assertThrows(
                        NotFoundException.class,
                        () -> anonymousAccessService.requirePublicReadableCalendar(calendar.getCalendarLinkToken())));
    }

    @Test
    void blankAndUnknownTokensReturnTheSameNotFoundResult() {
        CalendarAccessService accessService = accessService(
                entityManagerStub().singleResultNotFound("from Calendar calendarEntity"));

        assertAll(
                () -> assertThrows(NotFoundException.class, () -> accessService.requireCalendarReadableByLinkToken(null, null)),
                () -> assertThrows(NotFoundException.class, () -> accessService.requireCalendarReadableByLinkToken(null, "   ")),
                () -> assertThrows(
                        NotFoundException.class,
                        () -> accessService.requireCalendarReadableByLinkToken(null, "unknown-token")));
    }

    private static CalendarAccessService accessServiceReturningRole(CalendarRole role) {
        return accessService(entityManagerStub().singleResult("select calendarMembership.role", role));
    }

    private static CalendarAccessService accessService(EntityManagerStub entityManagerStub) {
        CalendarAccessService accessService = new CalendarAccessService();
        setField(accessService, "entityManager", entityManagerStub.entityManager());
        return accessService;
    }

    private static Calendar activeCalendar(boolean publicAccessEnabled) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, 10L);
        calendar.setName("Kayaking");
        calendar.setCalendarLinkToken("calendar-token-123456789012345678901234567890");
        calendar.setPublicAccessEnabled(publicAccessEnabled);
        calendar.setActive(true);
        return calendar;
    }

    private static ApplicationUser activeUser() {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, 20L);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }
}
