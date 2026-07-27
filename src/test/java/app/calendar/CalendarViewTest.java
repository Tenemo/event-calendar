package app.calendar;

import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.event.CalendarEvent;
import app.event.CalendarEventRow;
import app.membership.CalendarRole;
import app.user.ApplicationUser;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarViewTest {
    private static final long CALENDAR_ID = 42L;

    private final CalendarTimeService calendarTimeService = new CalendarTimeService();

    @Test
    void paginationPresentationTargetsTheFirstNewEventAndAnnouncesTheCount() {
        CalendarEventRow existingEvent = eventRow(10L, "Existing event", "2026-08-01T10:00:00Z");
        CalendarEventRow firstNewEvent = eventRow(20L, "First new event", "2026-08-02T10:00:00Z");
        CalendarEventRow secondNewEvent = eventRow(30L, "Second new event", "2026-08-03T10:00:00Z");
        CalendarView calendarView = new CalendarView();
        setField(
                calendarView,
                "events",
                List.of(existingEvent, firstNewEvent, secondNewEvent));

        calendarView.describePaginationResult(List.of(existingEvent), false);

        assertAll(
                () -> assertEquals(20L, calendarView.getFirstNewlyLoadedEventId()),
                () -> assertEquals(
                        "Loaded 2 more events. Total events shown: 3.",
                        calendarView.getEventPaginationAnnouncement()));
    }

    @Test
    void refreshedPaginationDoesNotMisidentifyPreviouslyVisibleEventsAsNew() {
        CalendarEventRow firstEvent = eventRow(10L, "First event", "2026-08-01T10:00:00Z");
        CalendarEventRow movedExistingEvent =
                eventRow(20L, "Moved event", "2026-07-31T10:00:00Z");
        CalendarEventRow newlyVisibleEvent =
                eventRow(30L, "New event", "2026-08-03T10:00:00Z");
        CalendarView calendarView = new CalendarView();
        setField(
                calendarView,
                "events",
                List.of(movedExistingEvent, firstEvent, newlyVisibleEvent));

        calendarView.describePaginationResult(
                List.of(firstEvent, movedExistingEvent),
                true);

        assertAll(
                () -> assertEquals(30L, calendarView.getFirstNewlyLoadedEventId()),
                () -> assertEquals(
                        "Events changed, so the list was refreshed. New events shown: 1. Total events shown: 3.",
                        calendarView.getEventPaginationAnnouncement()));
    }

    @Test
    void refreshedPaginationCountsNewIdentitiesRatherThanNetListGrowth() {
        CalendarEventRow deletedVisibleEvent =
                eventRow(10L, "Deleted visible event", "2026-08-01T10:00:00Z");
        CalendarEventRow retainedVisibleEvent =
                eventRow(20L, "Retained visible event", "2026-08-02T10:00:00Z");
        CalendarEventRow insertedEarlierEvent =
                eventRow(30L, "Inserted earlier event", "2026-07-31T10:00:00Z");
        CalendarEventRow firstNewTailEvent =
                eventRow(40L, "First new tail event", "2026-08-03T10:00:00Z");
        CalendarEventRow secondNewTailEvent =
                eventRow(50L, "Second new tail event", "2026-08-04T10:00:00Z");
        CalendarView calendarView = new CalendarView();
        setField(
                calendarView,
                "events",
                List.of(
                        insertedEarlierEvent,
                        retainedVisibleEvent,
                        firstNewTailEvent,
                        secondNewTailEvent));

        calendarView.describePaginationResult(
                List.of(deletedVisibleEvent, retainedVisibleEvent),
                true);

        assertAll(
                () -> assertEquals(30L, calendarView.getFirstNewlyLoadedEventId()),
                () -> assertEquals(
                        "Events changed, so the list was refreshed. New events shown: 3. Total events shown: 4.",
                        calendarView.getEventPaginationAnnouncement()));
    }

    @Test
    void concurrentLinkRegenerationRecoversTheCurrentAuthorizedCanonicalRoute() {
        String currentCalendarLinkToken = "CurrentAbc0";
        ApplicationUser actingUser = new ApplicationUser();
        CalendarView calendarView = calendarViewWithMemberships(List.of(
                new CalendarMembershipSummary(
                        7L,
                        "Other calendar",
                        "OtherLinkA0",
                        CalendarRole.ADMIN,
                        true),
                new CalendarMembershipSummary(
                        CALENDAR_ID,
                        "Recovered calendar",
                        currentCalendarLinkToken,
                        CalendarRole.EDITOR,
                        true)));

        assertEquals(
                "/" + currentCalendarLinkToken,
                calendarView.currentMemberCalendarRoute(actingUser));
    }

    @Test
    void linkRegenerationRecoveryNeverUsesAFormerMembersOrMalformedCalendarLink() {
        ApplicationUser actingUser = new ApplicationUser();
        CalendarView formerMemberCalendarView = calendarViewWithMemberships(List.of(
                new CalendarMembershipSummary(
                        7L,
                        "Different calendar",
                        "OtherLinkA0",
                        CalendarRole.ADMIN,
                        true)));
        CalendarView malformedTokenCalendarView = calendarViewWithMemberships(List.of(
                new CalendarMembershipSummary(
                        CALENDAR_ID,
                        "Malformed calendar",
                        "not-a-calendar-link",
                        CalendarRole.EDITOR,
                        true)));

        assertAll(
                () -> assertEquals(
                        "/app/calendars",
                        formerMemberCalendarView.currentMemberCalendarRoute(actingUser)),
                () -> assertEquals(
                        "/app/calendars",
                        malformedTokenCalendarView.currentMemberCalendarRoute(actingUser)),
                () -> assertEquals(
                        "/app/calendars",
                        malformedTokenCalendarView.currentMemberCalendarRoute(null)));
    }

    private static CalendarView calendarViewWithMemberships(
            List<CalendarMembershipSummary> calendarMemberships) {
        CalendarView calendarView = new CalendarView();
        setField(calendarView, "calendarId", CALENDAR_ID);
        setField(calendarView, "calendarService", new FixedCalendarService(calendarMemberships));
        return calendarView;
    }

    private CalendarEventRow eventRow(Long eventId, String title, String startTime) {
        CalendarEvent event = new CalendarEvent();
        setEntityId(event, eventId);
        event.setTitle(title);
        event.setStartTime(OffsetDateTime.parse(startTime));
        event.setEndTime(OffsetDateTime.parse(startTime).plusHours(1));
        return CalendarEventRow.from(event, "UTC", calendarTimeService);
    }

    private static final class FixedCalendarService extends CalendarService {
        private final List<CalendarMembershipSummary> calendarMemberships;

        private FixedCalendarService(List<CalendarMembershipSummary> calendarMemberships) {
            this.calendarMemberships = calendarMemberships;
        }

        @Override
        public List<CalendarMembershipSummary> findCalendarsForUser(
                ApplicationUser user) {
            return calendarMemberships;
        }
    }
}
