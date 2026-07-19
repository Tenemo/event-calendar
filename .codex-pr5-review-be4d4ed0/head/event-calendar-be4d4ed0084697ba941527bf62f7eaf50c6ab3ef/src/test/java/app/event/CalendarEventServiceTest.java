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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceTest {
    @Test
    void acceptsEventsWithTitleAndStrictlyIncreasingTimes() {
        Calendar calendar = activeCalendar(200L);
        LocalDateTime startTime = LocalDateTime.parse("2026-07-08T12:00:00");
        LocalDateTime endTime = startTime.plusHours(2);

        CalendarEvent createdEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Kayaking",
                null,
                null,
                new EventTimeInput.Timed(startTime, endTime),
                calendar.getVersion(),
                calendar.getTimeZone());

        assertAll(
                () -> assertEquals("Kayaking", createdEvent.getTitle()),
                () -> assertEquals(OffsetDateTime.parse("2026-07-08T12:00:00+02:00"), createdEvent.getStartTime()),
                () -> assertEquals(OffsetDateTime.parse("2026-07-08T14:00:00+02:00"), createdEvent.getEndTime()));
    }

    @Test
    void storesSubmittedAllDayCivilDatesAsOneFullCalendarDay() {
        Calendar calendar = activeCalendar(200L);
        CalendarEvent createdEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Kayaking",
                null,
                null,
                new EventTimeInput.AllDay(
                        LocalDate.parse("2026-07-08"),
                        LocalDate.parse("2026-07-08")),
                calendar.getVersion(),
                calendar.getTimeZone());

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
                new EventTimeInput.AllDay(
                        LocalDate.parse("2026-03-28"),
                        LocalDate.parse("2026-03-29")),
                calendar.getVersion(),
                calendar.getTimeZone());
        CalendarEvent autumnEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Autumn weekend",
                null,
                null,
                new EventTimeInput.AllDay(
                        LocalDate.parse("2026-10-24"),
                        LocalDate.parse("2026-10-25")),
                calendar.getVersion(),
                calendar.getTimeZone());

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
    void storesAValidAllDayEventImmediatelyBeforeASkippedCivilDate() {
        Calendar calendar = activeCalendar(200L);
        calendar.setTimeZone("Pacific/Apia");

        CalendarEvent createdEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Last day before the date-line move",
                null,
                null,
                new EventTimeInput.AllDay(
                        LocalDate.parse("2011-12-29"),
                        LocalDate.parse("2011-12-29")),
                calendar.getVersion(),
                calendar.getTimeZone());

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2011-12-29T00:00:00-10:00"),
                        createdEvent.getStartTime()),
                () -> assertEquals(
                        OffsetDateTime.parse("2011-12-31T00:00:00+14:00"),
                        createdEvent.getEndTime()),
                () -> assertEquals(Duration.ofHours(24), Duration.between(
                        createdEvent.getStartTime(), createdEvent.getEndTime())));
    }

    @Test
    void rejectsAllDayRangesWhoseLastCalendarDatePrecedesTheFirst() {
        Calendar calendar = activeCalendar(200L);
        CalendarEventService eventService = configuredCreationService(calendar);

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> eventService.createEvent(
                        activeUser(100L),
                        200L,
                        "Backwards trip",
                        null,
                        null,
                        new EventTimeInput.AllDay(
                                LocalDate.parse("2026-07-10"),
                                LocalDate.parse("2026-07-09")),
                        calendar.getVersion(),
                        calendar.getTimeZone()));

        assertEquals("Event last day must be on or after the first day.", exception.getMessage());
    }

    @Test
    void rejectsAllDaySubmissionWhenTheCalendarTimeZoneChangedWhileTheFormWasOpen() {
        Calendar calendar = activeCalendar(200L);
        String formTimeZone = calendar.getTimeZone();
        calendar.setTimeZone("Pacific/Honolulu");
        EntityManagerStub entityManagerStub = entityManagerStub();
        CalendarEventService eventService = configuredCreationService(calendar, entityManagerStub);

        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> eventService.createEvent(
                        activeUser(100L),
                        calendar.getId(),
                        "July 16",
                        null,
                        null,
                        new EventTimeInput.AllDay(
                                LocalDate.parse("2026-07-16"),
                                LocalDate.parse("2026-07-16")),
                        calendar.getVersion(),
                        formTimeZone));

        assertAll(
                () -> assertEquals(
                        "This calendar changed after you opened the event form. Reload the page and try again.",
                        exception.getMessage()),
                () -> assertEquals(List.of(), entityManagerStub.persistedObjects()));
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
                new EventTimeInput.AllDay(
                        LocalDate.parse("2026-07-22"),
                        LocalDate.parse("2026-07-24")),
                calendar.getVersion(),
                calendar.getTimeZone());
        OffsetDateTime originalStartTime = createdEvent.getStartTime();
        OffsetDateTime originalEndTime = createdEvent.getEndTime();
        CalendarTimeService calendarTimeService = new CalendarTimeService();
        CalendarEventRow displayedEvent = CalendarEventRow.from(
                createdEvent,
                calendar.getTimeZone(),
                calendarTimeService);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(CalendarEvent.class, createdEvent.getId(), createdEvent);
        CalendarEventService updateService = configuredUpdateService(entityManagerStub, calendar);

        CalendarEvent updatedEvent = updateService.updateEvent(
                activeUser(100L),
                createdEvent.getId(),
                createdEvent.getVersion(),
                createdEvent.getTitle(),
                createdEvent.getDescription(),
                createdEvent.getLocation(),
                new EventTimeInput.AllDay(
                        displayedEvent.getStartTime().toLocalDate(),
                        displayedEvent.getInclusiveEndDate()),
                calendar.getVersion(),
                calendar.getTimeZone());

        assertAll(
                () -> assertEquals(originalStartTime, updatedEvent.getStartTime()),
                () -> assertEquals(originalEndTime, updatedEvent.getEndTime()),
                () -> assertEquals("All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026", displayedEvent.getTimeLabel()));
    }

    @Test
    void memberQueriesStayCalendarScopedAndOrderedByStartTime() {
        EntityManagerStub entityManagerStub = entityManagerStub().resultList(
                "where calendarEvent.calendar.id = :calendarId "
                        + "order by calendarEvent.startTime",
                List.of());
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());

        List<CalendarEvent> events = eventService.findEventsForMember(
                activeUser(100L),
                200L);

        assertEquals(List.of(), events);
    }

    @Test
    void rejectsMissingTitlesAndInvalidTimeRanges() {
        LocalDateTime startTime = LocalDateTime.parse("2026-07-08T12:00:00");

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
        LocalDateTime startTime = LocalDateTime.parse("2026-07-08T12:00:00");
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
        LocalDateTime startTime = LocalDateTime.parse("2026-07-08T12:00:00");

        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        setField(eventService, "calendarService", new FixedCalendarService(calendar));
        setField(eventService, "calendarTimeService", new CalendarTimeService());
        setField(eventService, "auditService", auditService);

        CalendarEvent event = eventService.createEvent(
                activeUser(100L),
                calendar.getId(),
                " Kayaking ",
                " Paddle ",
                " River ",
                new EventTimeInput.Timed(startTime, startTime.plusHours(2)),
                calendar.getVersion(),
                calendar.getTimeZone());

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
        CalendarEventService staleVersionService = configuredUpdateService(staleVersionEntityManager, calendar);

        assertThrows(
                ConflictException.class,
                () -> staleVersionService.updateEvent(
                        activeUser(100L),
                        event.getId(),
                        99,
                        "Updated",
                        null,
                        null,
                        new EventTimeInput.Timed(
                                LocalDateTime.parse("2026-07-08T12:00:00"),
                                LocalDateTime.parse("2026-07-08T14:00:00")),
                        calendar.getVersion(),
                        calendar.getTimeZone()));

        EntityManagerStub providerConflictEntityManager = entityManagerStub()
                .find(CalendarEvent.class, event.getId(), event)
                .failOnFlush(new OptimisticLockException("stale row"));
        CalendarEventService providerConflictService = configuredUpdateService(providerConflictEntityManager, calendar);

        assertThrows(
                ConflictException.class,
                () -> providerConflictService.updateEvent(
                        activeUser(100L),
                        event.getId(),
                        event.getVersion(),
                        "Updated",
                        null,
                        null,
                        new EventTimeInput.Timed(
                                LocalDateTime.parse("2026-07-08T12:00:00"),
                                LocalDateTime.parse("2026-07-08T14:00:00")),
                        calendar.getVersion(),
                        calendar.getTimeZone()));
    }

    private static CalendarEventService configuredUpdateService(
            EntityManagerStub entityManagerStub,
            Calendar calendar) {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        setField(eventService, "calendarService", new FixedCalendarService(calendar));
        setField(eventService, "calendarTimeService", new CalendarTimeService());
        setField(eventService, "auditService", new NoOperationAuditService());
        return eventService;
    }

    private static CalendarEventService configuredCreationService(Calendar calendar) {
        return configuredCreationService(calendar, entityManagerStub());
    }

    private static CalendarEventService configuredCreationService(
            Calendar calendar,
            EntityManagerStub entityManagerStub) {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        setField(eventService, "calendarService", new FixedCalendarService(calendar));
        setField(eventService, "calendarTimeService", new CalendarTimeService());
        setField(eventService, "auditService", new NoOperationAuditService());
        return eventService;
    }

    private static CalendarEventService validationService() {
        return configuredCreationService(activeCalendar(200L));
    }

    private static void assertInvalidCreation(
            CalendarEventService eventService,
            String title,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        Calendar calendar = activeCalendar(200L);
        assertThrows(
                ValidationException.class,
                () -> eventService.createEvent(
                        activeUser(100L),
                        200L,
                        title,
                        null,
                        location,
                        new EventTimeInput.Timed(startTime, endTime),
                        calendar.getVersion(),
                        calendar.getTimeZone()));
    }

    private static Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setCalendarLinkToken("Abc_123-xY0");
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
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
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

    private static final class NoOperationAuditService extends AuditService {
        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
