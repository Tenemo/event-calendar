package app.event;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceAuthorizationTest {
    @Test
    void eventCreationRequiresEditorAccessBeforeValidationOrPersistence() {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "calendarAccessService", new RejectingAccessService());

        LocalDateTime startTime = LocalDateTime.parse("2026-07-08T12:00:00");

        assertThrows(
                AuthorizationException.class,
                () -> eventService.createEvent(
                        activeUser(),
                        10L,
                        "Kayaking",
                        null,
                        null,
                        new EventTimeInput.Timed(startTime, startTime.plusHours(1)),
                        0,
                        "Europe/Warsaw"));
    }

    @Test
    void inaccessibleAndMissingEventsUseTheSameNotFoundBoundary() {
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

        NotFoundException updateException = assertThrows(
                NotFoundException.class,
                () -> eventService.updateEvent(
                        activeUser(),
                        event.getId(),
                        event.getVersion(),
                        "Unauthorized change",
                        null,
                        null,
                        new EventTimeInput.Timed(
                                LocalDateTime.parse("2026-07-08T12:00:00"),
                                LocalDateTime.parse("2026-07-08T13:00:00")),
                        calendar.getVersion(),
                        "Europe/Warsaw"));
        NotFoundException deleteException = assertThrows(
                NotFoundException.class,
                () -> eventService.deleteEvent(activeUser(), event.getId(), event.getVersion()));
        NotFoundException missingException = assertThrows(
                NotFoundException.class,
                () -> eventService.deleteEvent(activeUser(), 31L, 0));

        assertAll(
                () -> assertEquals("Event was not found.", updateException.getMessage()),
                () -> assertEquals("Event was not found.", deleteException.getMessage()),
                () -> assertEquals("Event was not found.", missingException.getMessage()),
                () -> assertEquals("Original title", event.getTitle()),
                () -> assertFalse(entityManagerStub.removedObjects().contains(event)));
    }

    @Test
    void eventMutationsRecheckEditorPermissionAfterTakingTheCalendarLock() {
        Calendar calendar = new Calendar();
        setEntityId(calendar, 10L);
        calendar.setActive(true);
        CalendarEvent event = new CalendarEvent();
        setEntityId(event, 30L);
        event.setCalendar(calendar);
        event.setTitle("Original title");
        event.setStartTime(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        event.setEndTime(OffsetDateTime.parse("2026-07-08T13:00:00Z"));
        EntityManagerStub entityManagerStub = entityManagerStub().find(CalendarEvent.class, event.getId(), event);
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new EditAccessRevokedAfterInitialCheckAccessService());
        setField(eventService, "calendarService", new FixedCalendarService(calendar));

        AuthorizationException exception = assertThrows(
                AuthorizationException.class,
                () -> eventService.deleteEvent(activeUser(), event.getId(), event.getVersion()));

        assertAll(
                () -> assertEquals("Editor access is required.", exception.getMessage()),
                () -> assertFalse(entityManagerStub.removedObjects().contains(event)));
    }

    private static ApplicationUser activeUser() {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, 20L);
        user.setUsername("unrelated-user");
        user.setDisplayName("Unrelated user");
        user.setActive(true);
        return user;
    }

    private static final class RejectingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            throw new AuthorizationException("Editor access is required.");
        }
    }

    private static final class EditAccessRevokedAfterInitialCheckAccessService extends CalendarAccessService {
        private int permissionChecks;

        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            permissionChecks++;
            if (permissionChecks > 1) {
                throw new AuthorizationException("Editor access is required.");
            }
        }
    }

    private static final class FixedCalendarService extends CalendarService {
        private final Calendar calendar;

        private FixedCalendarService(Calendar calendar) {
            this.calendar = calendar;
        }

        @Override
        public Calendar requireActiveCalendarForChildMutation(Long calendarId) {
            return calendar;
        }
    }
}
