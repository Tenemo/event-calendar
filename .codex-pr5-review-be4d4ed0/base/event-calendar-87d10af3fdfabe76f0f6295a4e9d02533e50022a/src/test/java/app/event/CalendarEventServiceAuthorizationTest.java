package app.event;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.calendar.Calendar;
import app.membership.CalendarAccessService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceAuthorizationTest {
    @Test
    void eventCreationRequiresEditorAccessBeforeValidationOrPersistence() {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "calendarAccessService", new RejectingAccessService());

        OffsetDateTime startTime = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertThrows(
                AuthorizationException.class,
                () -> eventService.createEvent(activeUser(), 10L, "Kayaking", null, null, startTime, startTime.plusHours(1), false));
    }

    @Test
    void eventUpdatesAndDeletesRequireEditorAccessBeforeMutation() {
        Calendar calendar = new Calendar();
        setEntityId(calendar, 10L);
        CalendarEvent event = new CalendarEvent();
        setEntityId(event, 30L);
        event.setCalendar(calendar);
        event.setTitle("Original title");
        event.setStartTime(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        event.setEndTime(OffsetDateTime.parse("2026-07-08T13:00:00Z"));
        EntityManagerStub entityManagerStub = entityManagerStub().find(CalendarEvent.class, event.getId(), event);
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new RejectingAccessService());

        assertThrows(
                AuthorizationException.class,
                () -> eventService.updateEvent(
                        activeUser(),
                        event.getId(),
                        event.getVersion(),
                        "Unauthorized change",
                        null,
                        null,
                        event.getStartTime(),
                        event.getEndTime(),
                        false));
        assertThrows(
                AuthorizationException.class,
                () -> eventService.deleteEvent(activeUser(), event.getId(), event.getVersion()));

        assertEquals("Original title", event.getTitle());
        assertFalse(entityManagerStub.removedObjects().contains(event));
    }

    private static ApplicationUser activeUser() {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, 20L);
        user.setUsername("viewer");
        user.setDisplayName("Viewer");
        user.setActive(true);
        return user;
    }

    private static final class RejectingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            throw new AuthorizationException("Editor access is required.");
        }
    }
}
