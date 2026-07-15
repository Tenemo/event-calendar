package app.event;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.calendar.CalendarTimeService;
import app.membership.CalendarAccessService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.ApplicationUser;
import app.util.ConflictException;
import app.util.ValidationException;
import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceTest {
    @Test
    void acceptsEventsWithTitleAndStrictlyIncreasingTimes() {
        OffsetDateTime startTime = OffsetDateTime.parse("2026-07-08T12:00:00Z");
        OffsetDateTime endTime = startTime.plusHours(2);

        CalendarEvent createdEvent = configuredCreationService(activeCalendar(200L)).createEvent(
                activeUser(100L), 200L, "Kayaking", null, null, startTime, endTime, false);

        assertAll(
                () -> assertEquals("Kayaking", createdEvent.getTitle()),
                () -> assertEquals(startTime, createdEvent.getStartTime()),
                () -> assertEquals(endTime, createdEvent.getEndTime()));
    }

    @Test
    void normalizesArbitrarySameDayAllDayTimesToOneFullCalendarDay() {
        CalendarEvent createdEvent = configuredCreationService(activeCalendar(200L)).createEvent(
                activeUser(100L),
                200L,
                "Kayaking",
                null,
                null,
                OffsetDateTime.parse("2026-07-08T23:45:00+02:00"),
                OffsetDateTime.parse("2026-07-08T00:15:00+02:00"),
                true);

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-08T00:00:00+02:00"),
                        createdEvent.getStartTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-09T00:00:00+02:00"),
                        createdEvent.getEndTime()),
                () -> assertEquals(Duration.ofHours(24), Duration.between(
                        createdEvent.getStartTime(), createdEvent.getEndTime())));
    }

    @Test
    void normalizesAllDayBoundariesAcrossBothDaylightSavingTransitions() {
        Calendar calendar = activeCalendar(200L);
        CalendarEvent springEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Spring weekend",
                null,
                null,
                OffsetDateTime.parse("2026-03-28T15:00:00+01:00"),
                OffsetDateTime.parse("2026-03-29T18:00:00+02:00"),
                true);
        CalendarEvent autumnEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Autumn weekend",
                null,
                null,
                OffsetDateTime.parse("2026-10-24T15:00:00+02:00"),
                OffsetDateTime.parse("2026-10-25T18:00:00+01:00"),
                true);

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2026-03-28T00:00:00+01:00"),
                        springEvent.getStartTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-03-30T00:00:00+02:00"),
                        springEvent.getEndTime()),
                () -> assertEquals(Duration.ofHours(47), Duration.between(
                        springEvent.getStartTime(), springEvent.getEndTime())),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-10-24T00:00:00+02:00"),
                        autumnEvent.getStartTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-10-26T00:00:00+01:00"),
                        autumnEvent.getEndTime()),
                () -> assertEquals(Duration.ofHours(49), Duration.between(
                        autumnEvent.getStartTime(), autumnEvent.getEndTime())));
    }

    @Test
    void rejectsAllDayRangesWhoseLastCalendarDatePrecedesTheFirst() {
        CalendarEventService eventService = configuredCreationService(activeCalendar(200L));

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> eventService.createEvent(
                        activeUser(100L),
                        200L,
                        "Backwards trip",
                        null,
                        null,
                        OffsetDateTime.parse("2026-07-10T00:01:00+02:00"),
                        OffsetDateTime.parse("2026-07-09T23:59:00+02:00"),
                        true));

        assertEquals("Event last day must be on or after the first day.", exception.getMessage());
    }

    @Test
    void editingAnAllDayEventWithItsInclusiveDisplayDatesDoesNotGrowTheStoredRange() {
        Calendar calendar = activeCalendar(200L);
        CalendarEvent createdEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "River weekend",
                null,
                null,
                OffsetDateTime.parse("2026-07-22T09:00:00+02:00"),
                OffsetDateTime.parse("2026-07-24T17:00:00+02:00"),
                true);
        OffsetDateTime originalStartTime = createdEvent.getStartTime();
        OffsetDateTime originalEndTime = createdEvent.getEndTime();
        CalendarTimeService calendarTimeService = new CalendarTimeService();
        CalendarEventRow displayedEvent = CalendarEventRow.from(
                createdEvent,
                calendar.getTimeZone(),
                calendarTimeService);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(CalendarEvent.class, createdEvent.getId(), createdEvent);
        CalendarEventService updateService = configuredUpdateService(entityManagerStub);

        CalendarEvent updatedEvent = updateService.updateEvent(
                activeUser(100L),
                createdEvent.getId(),
                createdEvent.getVersion(),
                createdEvent.getTitle(),
                createdEvent.getDescription(),
                createdEvent.getLocation(),
                calendarTimeService.toStoredTime(
                        displayedEvent.getStartTime().toLocalDate().atTime(11, 30),
                        calendar.getTimeZone()),
                calendarTimeService.toStoredTime(
                        displayedEvent.getInclusiveEndDate().atTime(20, 45),
                        calendar.getTimeZone()),
                true);

        assertAll(
                () -> assertEquals(originalStartTime, updatedEvent.getStartTime()),
                () -> assertEquals(originalEndTime, updatedEvent.getEndTime()),
                () -> assertEquals("All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026", displayedEvent.getTimeLabel()));
    }

    @Test
    void memberRangeQueriesUseStrictOverlapBoundariesForExclusiveStoredEnds() {
        EntityManagerStub entityManagerStub = entityManagerStub().resultList(
                "calendarEvent.endTime > :rangeStartTime "
                        + "and calendarEvent.startTime < :rangeEndTime",
                List.of());
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        OffsetDateTime exclusiveEventEnd = OffsetDateTime.parse("2026-07-25T00:00:00+02:00");

        List<CalendarEvent> events = eventService.findMemberEvents(
                activeUser(100L),
                200L,
                exclusiveEventEnd,
                exclusiveEventEnd.plusDays(1));

        assertEquals(List.of(), events);
    }

    @Test
    void rejectsMissingTitlesAndInvalidTimeRanges() {
        OffsetDateTime startTime = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        CalendarEventService eventService = validationService();

        assertAll(
                () -> assertInvalidCreation(eventService, null, null, startTime, startTime.plusHours(1)),
                () -> assertInvalidCreation(eventService, "   ", null, startTime, startTime.plusHours(1)),
                () -> assertInvalidCreation(eventService, "Kayaking", null, null, startTime.plusHours(1)),
                () -> assertInvalidCreation(eventService, "Kayaking", null, startTime, null),
                () -> assertInvalidCreation(eventService, "Kayaking", null, startTime, startTime),
                () -> assertInvalidCreation(eventService, "Kayaking", null, startTime, startTime.minusMinutes(1)));
    }

    @Test
    void rejectsTitleAndLocationLongerThanTheSchemaAllowsBeforePersistence() {
        OffsetDateTime startTime = OffsetDateTime.parse("2026-07-08T12:00:00Z");
        CalendarEventService eventService = validationService();

        assertAll(
                () -> assertInvalidCreation(eventService, "K".repeat(201), null, startTime, startTime.plusHours(2)),
                () -> assertInvalidCreation(eventService, "Kayaking", "R".repeat(201), startTime, startTime.plusHours(2)));
    }

    @Test
    void eventCreationRecordsAuditLogWithGeneratedEventId() {
        EntityManagerStub entityManagerStub = entityManagerStub();
        Calendar calendar = activeCalendar(200L);
        RecordingAuditService auditService = new RecordingAuditService();
        CalendarEventService eventService = new CalendarEventService();
        OffsetDateTime startTime = OffsetDateTime.parse("2026-07-08T12:00:00Z");

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
                startTime,
                startTime.plusHours(2),
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
        event.setStartTime(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        event.setEndTime(OffsetDateTime.parse("2026-07-08T14:00:00Z"));

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
                        event.getStartTime(),
                        event.getEndTime(),
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
                        event.getStartTime(),
                        event.getEndTime(),
                        false));
    }

    private static CalendarEventService configuredUpdateService(EntityManagerStub entityManagerStub) {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        setField(eventService, "calendarTimeService", new CalendarTimeService());
        setField(eventService, "auditService", new NoopAuditService());
        return eventService;
    }

    private static CalendarEventService configuredCreationService(Calendar calendar) {
        CalendarEventService eventService = validationService();
        setField(eventService, "entityManager", entityManagerStub().entityManager());
        setField(eventService, "calendarService", new FixedCalendarService(calendar));
        setField(eventService, "calendarTimeService", new CalendarTimeService());
        setField(eventService, "auditService", new NoopAuditService());
        return eventService;
    }

    private static CalendarEventService validationService() {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        return eventService;
    }

    private static void assertInvalidCreation(
            CalendarEventService eventService,
            String title,
            String location,
            OffsetDateTime startTime,
            OffsetDateTime endTime) {
        assertThrows(
                ValidationException.class,
                () -> eventService.createEvent(
                        activeUser(100L), 200L, title, null, location, startTime, endTime, false));
    }

    private static Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setPublicToken("public-token-123456789012345678901234567890");
        calendar.setPublicAccessEnabled(true);
        calendar.setTimeZone("Europe/Warsaw");
        calendar.setActive(true);
        return calendar;
    }

    private static ApplicationUser activeUser(Long id) {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, id);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanView(ApplicationUser user, Long calendarId) {
        }

        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
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
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.action = action;
        }
    }

    private static final class NoopAuditService extends AuditService {
        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
