package app.event;

import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.membership.CalendarAccessService;
import app.user.AppUser;
import app.util.AuthorizationException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceAuthorizationTest {
    @Test
    void eventCreationRequiresEditorAccessBeforeValidationOrPersistence() {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "calendarAccessService", new RejectingAccessService());

        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertThrows(
                AuthorizationException.class,
                () -> eventService.createEvent(activeUser(), 10L, "Kayaking", null, null, startAt, startAt.plusHours(1), false));
    }

    private static AppUser activeUser() {
        AppUser user = new AppUser();
        setEntityId(user, 20L);
        user.setUsername("viewer");
        user.setDisplayName("Viewer");
        user.setActive(true);
        return user;
    }

    private static final class RejectingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(AppUser user, Long calendarId) {
            throw new AuthorizationException("Editor access is required.");
        }
    }
}
