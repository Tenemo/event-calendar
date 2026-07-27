package app.calendar;

import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.FacesContextWrapper;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.ExternalContextWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class CalendarSettingsViewTest {
    private static final long CALENDAR_ID = 42L;

    @Test
    void validationFailurePreservesSubmittedSettings() {
        ApplicationUser actingUser = new ApplicationUser();
        Calendar initiallyPersistedCalendar = calendar(
                "Initial calendar",
                "Initial description",
                "Europe/Warsaw",
                true,
                "InitialAbc0",
                3);
        Calendar successfullyUpdatedCalendar = calendar(
                "Persisted update",
                "Persisted description",
                "America/New_York",
                false,
                "UpdatedAbc0",
                4);
        RecordingCalendarService calendarService = new RecordingCalendarService(
                List.of(initiallyPersistedCalendar),
                List.of(
                        successfullyUpdatedCalendar,
                        new ValidationException("Time zone is invalid.")));
        CalendarSettingsView view = loadedView(actingUser, calendarService);

        view.setName("Submitted successful name");
        view.setDescription("Submitted successful description");
        view.setTimeZone("America/New_York");
        view.setPublicAccessEnabled(false);
        RecordingFacesContext successfulRequestContext = new RecordingFacesContext();
        try {
            view.save();
            assertFalse(successfulRequestContext.isValidationFailed());
        } finally {
            successfulRequestContext.release();
        }

        view.setName("Rejected name");
        view.setDescription("Rejected description");
        view.setTimeZone("Mars/Olympus");
        view.setPublicAccessEnabled(true);
        RecordingFacesContext rejectedRequestContext = new RecordingFacesContext();
        try {
            view.save();

            assertAll(
                    () -> assertEquals("Rejected name", view.getName()),
                    () -> assertEquals("Rejected description", view.getDescription()),
                    () -> assertEquals("Mars/Olympus", view.getTimeZone()),
                    () -> assertTrue(view.isPublicAccessEnabled()),
                    () -> assertTrue(view.isAvailable()),
                    () -> assertEquals(1, calendarService.requireAdminCalendarCallCount()),
                    () -> assertEquals(2, calendarService.updateRequests().size()),
                    () -> assertEquals(4, calendarService.updateRequests().get(1).expectedVersion()),
                    () -> assertRejectedSaveMessage(
                            rejectedRequestContext,
                            "Time zone is invalid."));
        } finally {
            rejectedRequestContext.release();
        }
    }

    @Test
    void conflictFailureReloadsAuthoritativeSettingsAndUsesTheirVersionForRetry() {
        ApplicationUser actingUser = new ApplicationUser();
        Calendar initiallyPersistedCalendar = calendar(
                "Initial calendar",
                "Initial description",
                "Europe/Warsaw",
                true,
                "InitialAbc0",
                5);
        Calendar concurrentlyUpdatedCalendar = calendar(
                "Concurrent calendar",
                "Concurrent description",
                "Pacific/Honolulu",
                false,
                "Concurrent0",
                6);
        Calendar retriedCalendar = calendar(
                "Retried calendar",
                "Concurrent description",
                "Pacific/Honolulu",
                false,
                "Concurrent0",
                7);
        RecordingCalendarService calendarService = new RecordingCalendarService(
                List.of(initiallyPersistedCalendar, concurrentlyUpdatedCalendar),
                List.of(
                        new ConflictException("The calendar changed."),
                        retriedCalendar));
        CalendarSettingsView view = loadedView(actingUser, calendarService);
        view.setName("Rejected stale name");
        view.setDescription("Rejected stale description");
        view.setTimeZone("Europe/London");
        view.setPublicAccessEnabled(true);

        RecordingFacesContext conflictRequestContext = new RecordingFacesContext();
        try {
            view.save();

            assertAll(
                    () -> assertEquals("Concurrent calendar", view.getName()),
                    () -> assertEquals("Concurrent description", view.getDescription()),
                    () -> assertEquals("Pacific/Honolulu", view.getTimeZone()),
                    () -> assertFalse(view.isPublicAccessEnabled()),
                    () -> assertEquals(2, calendarService.requireAdminCalendarCallCount()),
                    () -> assertRejectedSaveMessage(conflictRequestContext, "The calendar changed."));
        } finally {
            conflictRequestContext.release();
        }

        view.setName("Retried calendar");
        RecordingFacesContext retryRequestContext = new RecordingFacesContext();
        try {
            view.save();

            assertAll(
                    () -> assertFalse(retryRequestContext.isValidationFailed()),
                    () -> assertEquals(2, calendarService.updateRequests().size()),
                    () -> assertEquals(6, calendarService.updateRequests().get(1).expectedVersion()),
                    () -> assertEquals("Concurrent description", calendarService.updateRequests().get(1).description()),
                    () -> assertEquals("Pacific/Honolulu", calendarService.updateRequests().get(1).timeZone()),
                    () -> assertFalse(calendarService.updateRequests().get(1).publicAccessEnabled()),
                    () -> assertEquals("Retried calendar", view.getName()));
        } finally {
            retryRequestContext.release();
        }
    }

    @ParameterizedTest
    @MethodSource("authorizationAndAvailabilityFailures")
    void authorizationAndNotFoundFailuresMakeTheViewUnavailable(
            RuntimeException rejectedSaveFailure,
            String expectedMessage) {
        ApplicationUser actingUser = new ApplicationUser();
        Calendar initiallyPersistedCalendar = calendar(
                "Persisted calendar",
                "Persisted description",
                "Europe/Warsaw",
                true,
                "PersistedA0",
                8);
        RecordingCalendarService calendarService = new RecordingCalendarService(
                List.of(initiallyPersistedCalendar),
                List.of(rejectedSaveFailure));
        CalendarSettingsView view = loadedView(actingUser, calendarService);
        view.setName("Rejected name");
        view.setDescription("Rejected description");
        view.setTimeZone("America/Los_Angeles");
        view.setPublicAccessEnabled(false);

        RecordingFacesContext rejectedRequestContext = new RecordingFacesContext();
        try {
            view.save();

            assertAll(
                    () -> assertFalse(view.isAvailable()),
                    () -> assertEquals(
                            HttpServletResponse.SC_NOT_FOUND,
                            rejectedRequestContext.responseStatus()),
                    () -> assertEquals(1, calendarService.requireAdminCalendarCallCount()),
                    () -> assertRejectedSaveMessage(rejectedRequestContext, expectedMessage));
        } finally {
            rejectedRequestContext.release();
        }
    }

    @Test
    void conflictRefreshFailureMakesTheViewUnavailable() {
        ApplicationUser actingUser = new ApplicationUser();
        Calendar initiallyPersistedCalendar = calendar(
                "Persisted calendar",
                "Persisted description",
                "Europe/Warsaw",
                true,
                "PersistedA0",
                9);
        RecordingCalendarService calendarService = new RecordingCalendarService(
                List.of(
                        initiallyPersistedCalendar,
                        new AuthorizationException("Calendar administration access was removed.")),
                List.of(new ConflictException("The calendar changed.")));
        CalendarSettingsView view = loadedView(actingUser, calendarService);
        view.setName("Rejected stale name");
        view.setDescription("Rejected stale description");
        view.setTimeZone("Europe/London");
        view.setPublicAccessEnabled(false);

        RecordingFacesContext rejectedRequestContext = new RecordingFacesContext();
        try {
            view.save();

            assertAll(
                    () -> assertFalse(view.isAvailable()),
                    () -> assertEquals(
                            HttpServletResponse.SC_NOT_FOUND,
                            rejectedRequestContext.responseStatus()),
                    () -> assertEquals(2, calendarService.requireAdminCalendarCallCount()),
                    () -> assertRejectedSaveMessage(rejectedRequestContext, "The calendar changed."));
        } finally {
            rejectedRequestContext.release();
        }
    }

    private static Stream<Arguments> authorizationAndAvailabilityFailures() {
        return Stream.of(
                Arguments.of(
                        new AuthorizationException("Calendar administration access was removed."),
                        "Calendar administration access was removed."),
                Arguments.of(
                        new NotFoundException("Calendar was not found."),
                        "Calendar was not found."));
    }

    private static CalendarSettingsView loadedView(
            ApplicationUser actingUser,
            RecordingCalendarService calendarService) {
        CalendarSettingsView view = new CalendarSettingsView();
        setField(view, "currentUser", new FixedCurrentUser(actingUser));
        setField(view, "calendarService", calendarService);
        view.setCalendarIdParameter(Long.toString(CALENDAR_ID));
        view.load();
        assertTrue(view.isAvailable());
        return view;
    }

    private static Calendar calendar(
            String name,
            String description,
            String timeZone,
            boolean publicAccessEnabled,
            String calendarLinkToken,
            int version) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, CALENDAR_ID);
        setField(calendar, "version", version);
        calendar.setName(name);
        calendar.setDescription(description);
        calendar.setTimeZone(timeZone);
        calendar.setPublicAccessEnabled(publicAccessEnabled);
        calendar.setCalendarLinkToken(calendarLinkToken);
        return calendar;
    }

    private static void assertRejectedSaveMessage(
            RecordingFacesContext facesContext,
            String expectedDetail) {
        assertAll(
                () -> assertTrue(facesContext.isValidationFailed()),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertEquals(
                        FacesMessage.SEVERITY_ERROR,
                        facesContext.messages().getFirst().getSeverity()),
                () -> assertEquals(
                        "Settings could not be saved.",
                        facesContext.messages().getFirst().getSummary()),
                () -> assertEquals(
                        expectedDetail,
                        facesContext.messages().getFirst().getDetail()));
    }

    private static final class FixedCurrentUser extends CurrentUser {
        private final ApplicationUser actingUser;

        private FixedCurrentUser(ApplicationUser actingUser) {
            this.actingUser = actingUser;
        }

        @Override
        public ApplicationUser require() {
            return actingUser;
        }
    }

    private static final class RecordingCalendarService extends CalendarService {
        private final List<?> requireAdminCalendarOutcomes;
        private final List<?> updateOutcomes;
        private final List<SettingsUpdateRequest> updateRequests = new ArrayList<>();
        private int requireAdminCalendarCallCount;
        private int updateCallCount;

        private RecordingCalendarService(
                List<?> requireAdminCalendarOutcomes,
                List<?> updateOutcomes) {
            this.requireAdminCalendarOutcomes = requireAdminCalendarOutcomes;
            this.updateOutcomes = updateOutcomes;
        }

        @Override
        public Calendar requireAdminCalendar(ApplicationUser actingUser, Long calendarId) {
            assertEquals(CALENDAR_ID, calendarId);
            Object outcome = requireAdminCalendarOutcomes.get(requireAdminCalendarCallCount++);
            return calendarOutcome(outcome);
        }

        @Override
        public Calendar updateCalendarSettings(
                ApplicationUser actingUser,
                Long calendarId,
                String name,
                String description,
                String timeZone,
                boolean publicAccessEnabled,
                Integer expectedVersion) {
            updateRequests.add(new SettingsUpdateRequest(
                    calendarId,
                    name,
                    description,
                    timeZone,
                    publicAccessEnabled,
                    expectedVersion));
            Object outcome = updateOutcomes.get(updateCallCount++);
            return calendarOutcome(outcome);
        }

        private Calendar calendarOutcome(Object outcome) {
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            return (Calendar) outcome;
        }

        private int requireAdminCalendarCallCount() {
            return requireAdminCalendarCallCount;
        }

        private List<SettingsUpdateRequest> updateRequests() {
            return updateRequests;
        }
    }

    private record SettingsUpdateRequest(
            Long calendarId,
            String name,
            String description,
            String timeZone,
            boolean publicAccessEnabled,
            Integer expectedVersion) {
    }

    private static final class RecordingFacesContext extends FacesContextWrapper {
        private final List<FacesMessage> messages = new ArrayList<>();
        private final RecordingExternalContext externalContext = new RecordingExternalContext();
        private boolean validationFailed;

        private RecordingFacesContext() {
            super(null);
            setCurrentInstance(this);
        }

        @Override
        public FacesContext getWrapped() {
            return null;
        }

        @Override
        public ExternalContext getExternalContext() {
            return externalContext;
        }

        @Override
        public void addMessage(String clientId, FacesMessage message) {
            messages.add(message);
        }

        @Override
        public void validationFailed() {
            validationFailed = true;
        }

        @Override
        public boolean isValidationFailed() {
            return validationFailed;
        }

        private List<FacesMessage> messages() {
            return messages;
        }

        private int responseStatus() {
            return externalContext.responseStatus;
        }

        @Override
        public void release() {
            setCurrentInstance(null);
        }
    }

    @SuppressWarnings("unchecked")
    private static final class RecordingExternalContext extends ExternalContextWrapper {
        private int responseStatus;

        private RecordingExternalContext() {
            super(null);
        }

        @Override
        public ExternalContext getWrapped() {
            return null;
        }

        @Override
        public void setResponseStatus(int statusCode) {
            responseStatus = statusCode;
        }
    }
}
