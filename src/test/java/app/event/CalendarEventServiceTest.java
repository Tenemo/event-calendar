package app.event;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.queryParameter;
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
import app.util.TextNormalizer;
import app.util.ValidationException;
import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
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
                () -> assertEquals("All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026", displayedEvent.getScheduleLabel()));
    }

    @Test
    void memberQueriesStayCalendarScopedAndOrderedByStartTime() {
        CalendarEvent firstEvent = persistedEvent(301L, "2026-08-01T10:00:00Z");
        CalendarEvent secondEvent = persistedEvent(302L, "2026-08-01T10:00:00Z");
        CalendarEvent extraEvent = persistedEvent(303L, "2026-08-02T10:00:00Z");
        EntityManagerStub entityManagerStub = entityManagerStub().resultList(
                "where calendarEvent.calendar.id = :calendarId "
                        + "order by calendarEvent.startTime, calendarEvent.id",
                List.of(firstEvent, secondEvent, extraEvent),
                queryParameter("calendarId", 200L));
        CalendarEventRevision eventRevision = new CalendarEventRevision(3, 303, 0);
        CalendarEventService eventService = paginationService(
                entityManagerStub,
                eventRevision,
                eventRevision);

        CalendarEventPage eventPage = eventService.findEventsForMember(
                activeUser(100L),
                200L,
                null,
                null,
                2);

        assertAll(
                () -> assertEquals(List.of(firstEvent, secondEvent), eventPage.events()),
                () -> assertEquals(true, eventPage.hasMore()),
                () -> assertEquals(
                        new CalendarEventCursor(secondEvent.getStartTime(), secondEvent.getId()),
                        eventPage.nextCursor()),
                () -> assertEquals(eventRevision, eventPage.revision()),
                () -> assertEquals(false, eventPage.restartRequired()),
                () -> assertEquals(
                        List.of(new app.testsupport.ServiceTestSupport.QueryPagination(
                                "select calendarEvent from CalendarEvent calendarEvent "
                                        + "where calendarEvent.calendar.id = :calendarId "
                                        + "order by calendarEvent.startTime, calendarEvent.id",
                                0,
                                3)),
                        entityManagerStub.queryPaginations()));
    }

    @Test
    void memberQueriesContinueStrictlyAfterTheStableEventCursor() {
        OffsetDateTime cursorStartTime = OffsetDateTime.parse("2026-08-01T10:00:00Z");
        CalendarEventCursor afterCursor = new CalendarEventCursor(cursorStartTime, 302L);
        CalendarEvent nextEvent = persistedEvent(303L, "2026-08-01T10:00:00Z");
        EntityManagerStub entityManagerStub = entityManagerStub().resultList(
                "and calendarEvent.startTime >= :afterStartTime "
                        + "and (calendarEvent.startTime > :afterStartTime "
                        + "or (calendarEvent.startTime = :afterStartTime "
                        + "and calendarEvent.id > :afterEventId))",
                List.of(nextEvent),
                queryParameter("calendarId", 200L),
                queryParameter("afterStartTime", cursorStartTime),
                queryParameter("afterEventId", 302L));
        CalendarEventRevision eventRevision = new CalendarEventRevision(1, 303, 0);
        CalendarEventService eventService = paginationService(
                entityManagerStub,
                eventRevision,
                eventRevision);

        CalendarEventPage eventPage = eventService.findEventsForMember(
                activeUser(100L),
                200L,
                afterCursor,
                null,
                50);

        assertAll(
                () -> assertEquals(List.of(nextEvent), eventPage.events()),
                () -> assertEquals(false, eventPage.hasMore()),
                () -> assertEquals(
                        new CalendarEventCursor(nextEvent.getStartTime(), nextEvent.getId()),
                        eventPage.nextCursor()),
                () -> assertEquals(eventRevision, eventPage.revision()),
                () -> assertEquals(false, eventPage.restartRequired()),
                () -> assertEquals(0, entityManagerStub.queryPaginations().getFirst().firstResult()),
                () -> assertEquals(51, entityManagerStub.queryPaginations().getFirst().maximumResults()));
    }

    @Test
    void requestsACompleteRestartWhenAnEventMovedAcrossTheCursorBeforeTheNextPage() {
        OffsetDateTime cursorStartTime = OffsetDateTime.parse("2026-08-01T10:00:00Z");
        CalendarEventCursor afterCursor = new CalendarEventCursor(cursorStartTime, 302L);
        CalendarEventRevision originalRevision = new CalendarEventRevision(102, 402, 0);
        CalendarEventRevision revisionAfterMovingAnEvent =
                new CalendarEventRevision(102, 402, 1);
        EntityManagerStub entityManagerStub = entityManagerStub();
        CalendarEventService eventService = paginationService(
                entityManagerStub,
                revisionAfterMovingAnEvent);

        CalendarEventPage eventPage = eventService.findEventsForMember(
                activeUser(100L),
                200L,
                afterCursor,
                originalRevision,
                50);

        assertAll(
                () -> assertEquals(true, eventPage.restartRequired()),
                () -> assertEquals(revisionAfterMovingAnEvent, eventPage.revision()),
                () -> assertEquals(List.of(), eventPage.events()),
                () -> assertEquals(List.of(), entityManagerStub.queryPaginations()));
    }

    @Test
    void discardsAPageWhenAnEventMovesBetweenThePageQueryAndFinalRevisionRead() {
        CalendarEvent eventReadDuringRace = persistedEvent(303L, "2026-08-01T10:00:00Z");
        CalendarEventRevision originalRevision = new CalendarEventRevision(102, 402, 0);
        CalendarEventRevision revisionAfterMovingAnEvent =
                new CalendarEventRevision(102, 402, 1);
        EntityManagerStub entityManagerStub = entityManagerStub().resultList(
                "order by calendarEvent.startTime, calendarEvent.id",
                List.of(eventReadDuringRace),
                queryParameter("calendarId", 200L));
        CalendarEventService eventService = paginationService(
                entityManagerStub,
                originalRevision,
                revisionAfterMovingAnEvent);

        CalendarEventPage eventPage = eventService.findEventsForMember(
                activeUser(100L),
                200L,
                null,
                originalRevision,
                50);

        assertAll(
                () -> assertEquals(true, eventPage.restartRequired()),
                () -> assertEquals(revisionAfterMovingAnEvent, eventPage.revision()),
                () -> assertEquals(List.of(), eventPage.events()),
                () -> assertEquals(1, entityManagerStub.queryPaginations().size()));
    }

    @Test
    void eventRevisionUsesBoundedCalendarAndEventAggregates() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult(
                        "select count(calendarEvent), max(calendarEvent.id)",
                        new Object[] {102L, 402L, 19L},
                        queryParameter("calendarId", 200L));
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());

        CalendarEventRevision revision = eventService.findEventRevision(200L);

        assertEquals(new CalendarEventRevision(102, 402, 19), revision);
    }

    @Test
    void emptyCalendarHasAStableZeroEventRevision() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult(
                        "select count(calendarEvent), max(calendarEvent.id)",
                        new Object[] {0L, null, null},
                        queryParameter("calendarId", 200L));
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub.entityManager());

        CalendarEventRevision revision = eventService.findEventRevision(200L);

        assertEquals(new CalendarEventRevision(0, 0, 0), revision);
    }

    @Test
    void rejectsUnboundedOrInvalidEventPageRequests() {
        CalendarEventService eventService = new CalendarEventService();
        setField(eventService, "entityManager", entityManagerStub().entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> eventService.findEventsForMember(
                                activeUser(100L), 200L, null, null, 0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> eventService.findEventsForMember(
                                activeUser(100L), 200L, null, null, 101)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CalendarEventCursor(
                                OffsetDateTime.parse("2026-08-01T10:00:00Z"),
                                0L)));
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
    void eventDescriptionsPreserveMultilineUnicodeAtTheSharedLimitAndRejectLongerValues() {
        Calendar calendar = activeCalendar(200L);
        LocalDateTime startTime = LocalDateTime.parse("2026-07-08T12:00:00");
        String unicodePrefix = "Zażółć gęślą jaźń\n東京\n\uD801\uDC37";
        String normalizedMaximumLengthDescription = unicodePrefix
                + "d".repeat(TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH - unicodePrefix.length());
        String browserPostedMaximumLengthDescription =
                normalizedMaximumLengthDescription.replace("\n", "\r\n");
        CalendarEvent createdEvent = configuredCreationService(calendar).createEvent(
                activeUser(100L),
                calendar.getId(),
                "Kayaking",
                browserPostedMaximumLengthDescription,
                null,
                new EventTimeInput.Timed(startTime, startTime.plusHours(2)),
                calendar.getVersion(),
                calendar.getTimeZone());

        ValidationException creationException = assertThrows(
                ValidationException.class,
                () -> configuredCreationService(calendar).createEvent(
                        activeUser(100L),
                        calendar.getId(),
                        "Kayaking",
                        browserPostedMaximumLengthDescription + "x",
                        null,
                        new EventTimeInput.Timed(startTime, startTime.plusHours(2)),
                        calendar.getVersion(),
                        calendar.getTimeZone()));
        CalendarEventService updateService = configuredUpdateService(
                entityManagerStub().find(CalendarEvent.class, createdEvent.getId(), createdEvent),
                calendar);
        ValidationException updateException = assertThrows(
                ValidationException.class,
                () -> updateService.updateEvent(
                        activeUser(100L),
                        createdEvent.getId(),
                        createdEvent.getVersion(),
                        createdEvent.getTitle(),
                        browserPostedMaximumLengthDescription + "x",
                        createdEvent.getLocation(),
                        new EventTimeInput.Timed(startTime, startTime.plusHours(2)),
                        calendar.getVersion(),
                        calendar.getTimeZone()));

        assertAll(
                () -> assertEquals(
                        TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                        normalizedMaximumLengthDescription.length()),
                () -> assertEquals(
                        TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH + 2,
                        browserPostedMaximumLengthDescription.length()),
                () -> assertEquals(normalizedMaximumLengthDescription, createdEvent.getDescription()),
                () -> assertEquals(
                        "Event description must be 4,000 characters or fewer.",
                        creationException.getMessage()),
                () -> assertEquals(creationException.getMessage(), updateException.getMessage()));
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

    private static CalendarEventService paginationService(
            EntityManagerStub entityManagerStub,
            CalendarEventRevision... revisions) {
        CalendarEventService eventService =
                new RevisionControlledCalendarEventService(List.of(revisions));
        setField(eventService, "entityManager", entityManagerStub.entityManager());
        setField(eventService, "calendarAccessService", new AllowingAccessService());
        return eventService;
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

    private static CalendarEvent persistedEvent(Long id, String startTime) {
        CalendarEvent event = new CalendarEvent();
        setEntityId(event, id);
        event.setStartTime(OffsetDateTime.parse(startTime));
        return event;
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

    private static final class RevisionControlledCalendarEventService
            extends CalendarEventService {
        private final Deque<CalendarEventRevision> revisions;

        private RevisionControlledCalendarEventService(
                List<CalendarEventRevision> revisions) {
            this.revisions = new ArrayDeque<>(revisions);
        }

        @Override
        CalendarEventRevision findEventRevision(Long calendarId) {
            if (revisions.isEmpty()) {
                throw new AssertionError("An unexpected event revision was requested.");
            }
            return revisions.removeFirst();
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
