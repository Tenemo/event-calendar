package app.event;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.AppUser;
import app.util.ValidationException;
import app.util.ConflictException;
import jakarta.persistence.OptimisticLockException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceTest {
    private final CalendarEventService calendarEventService = new CalendarEventService();

    @Test
    void acceptsEventsWithTitleAndStrictlyIncreasingTimes() {
        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");
        OffsetDateTime endAt = startAt.plusHours(2);

        assertDoesNotThrow(() -> calendarEventService.validateEvent("Kayaking", startAt, endAt));
    }

    @Test
    void rejectsMissingTitlesAndInvalidTimeRanges() {
        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertAll(
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent(null, startAt, startAt.plusHours(1))),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("   ", startAt, startAt.plusHours(1))),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", null, startAt.plusHours(1))),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", startAt, null)),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", startAt, startAt)),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", startAt, startAt.minusMinutes(1))));
    }

    @Test
    void rejectsTitleAndLocationLongerThanTheSchemaAllowsBeforePersistence() {
        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "calendarAccessService", new AllowingAccessService());

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarEventService.validateEvent("K".repeat(201), startAt, startAt.plusHours(2))),
                () -> assertThrows(
                        ValidationException.class,
                        () -> eventService.createEvent(
                                activeUser(100L),
                                200L,
                                "Kayaking",
                                null,
                                "R".repeat(201),
                                startAt,
                                startAt.plusHours(2),
                                false)));
    }

    @Test
    void eventCreationRecordsAuditLogWithGeneratedEventId() {
        EntityManagerStub entityManagerStub = entityManagerStub();
        Calendar calendar = activeCalendar(200L);
        RecordingAuditService auditService = new RecordingAuditService();
        CalendarEventService eventService = new CalendarEventService();
        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        setField(eventService, "calendarService", new FixedCalendarService(calendar));
        setField(eventService, "auditService", auditService);

        CalendarEvent event = eventService.createEvent(
                activeUser(100L),
                calendar.getId(),
                " Kayaking ",
                " Paddle ",
                " River ",
                startAt,
                startAt.plusHours(2),
                false);

        assertAll(
                () -> assertNotNull(event.getId()),
                () -> assertEquals(1, entityManagerStub.flushCount()),
                () -> assertEquals(event.getId(), auditService.entityId),
                () -> assertEquals("calendar_event", auditService.entityType),
                () -> assertEquals("created", auditService.action),
                () -> assertEquals(ZoneOffset.UTC, event.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, event.getUpdatedAt().getOffset()));
    }

    @Test
    void rejectsStaleEventVersionsAndTranslatesProviderOptimisticLockFailures() {
        Calendar calendar = activeCalendar(200L);
        CalendarEvent event = new CalendarEvent();
        setEntityId(event, 300L);
        event.setCalendar(calendar);
        event.setTitle("Kayaking");
        event.setStartAt(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        event.setEndAt(OffsetDateTime.parse("2026-07-08T14:00:00Z"));

        EntityManagerStub staleVersionEntityManager = entityManagerStub()
                .find(CalendarEvent.class, event.getId(), event);
        CalendarEventService staleVersionService = configuredUpdateService(staleVersionEntityManager);

        assertThrows(
                ConflictException.class,
                () -> staleVersionService.updateEvent(
                        activeUser(100L),
                        event.getId(),
                        99,
                        "Updated",
                        null,
                        null,
                        event.getStartAt(),
                        event.getEndAt(),
                        false));

        EntityManagerStub providerConflictEntityManager = entityManagerStub()
                .find(CalendarEvent.class, event.getId(), event)
                .failOnFlush(new OptimisticLockException("stale row"));
        CalendarEventService providerConflictService = configuredUpdateService(providerConflictEntityManager);

        assertThrows(
                ConflictException.class,
                () -> providerConflictService.updateEvent(
                        activeUser(100L),
                        event.getId(),
                        event.getVersion(),
                        "Updated",
                        null,
                        null,
                        event.getStartAt(),
                        event.getEndAt(),
                        false));
    }

    private static CalendarEventService configuredUpdateService(EntityManagerStub entityManagerStub) {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        setField(eventService, "auditService", new NoopAuditService());
        return eventService;
    }

    private static Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setPublicToken("public-token-123456789012345678901234567890");
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        return calendar;
    }

    private static AppUser activeUser(Long id) {
        AppUser user = new AppUser();
        setEntityId(user, id);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(AppUser user, Long calendarId) {
        }
    }

    private static final class FixedCalendarService extends CalendarService {
        private final Calendar calendar;

        private FixedCalendarService(Calendar calendar) {
            this.calendar = calendar;
        }

        @Override
        public Calendar requireActiveCalendar(Long calendarId) {
            return calendar;
        }
    }

    private static final class RecordingAuditService extends AuditService {
        private String entityType;
        private Long entityId;
        private String action;

        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.action = action;
        }
    }

    private static final class NoopAuditService extends AuditService {
        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
