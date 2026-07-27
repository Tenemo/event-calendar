package app.security;

import static app.testsupport.ProxyReturnValues.defaultValue;
import static app.testsupport.XmlTestDocuments.parseXml;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.calendar.CalendarRouteFilter;
import jakarta.faces.FacesException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.ExternalContextWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.FacesContextWrapper;
import jakarta.faces.context.Flash;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import jakarta.faces.event.PhaseId;
import jakarta.faces.event.SystemEvent;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class ExpiredSignInViewExceptionHandlerTest {
    private static final Path FACES_CONFIGURATION_PATH =
            Path.of("src", "main", "webapp", "WEB-INF", "faces-config.xml");
    private static final Path SIGN_IN_VIEW_PATH =
            Path.of("src", "main", "webapp", "login.xhtml");
    private static final Path REGISTRATION_VIEW_PATH =
            Path.of("src", "main", "webapp", "register.xhtml");

    @Test
    void handleRemovesEveryRecoverableEventAndWritesOneRelativeRedirect() {
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request("POST", "/calendar", "/calendar/login"),
                responseRecorder.response(),
                "/calendar");
        ExceptionQueuedEvent firstRecoverableEvent = event(
                facesContext,
                new ViewExpiredException("The first sign-in view expired.", "/login"));
        ExceptionQueuedEvent secondRecoverableEvent = event(
                facesContext,
                new ServletException(
                        "The second Faces postback failed.",
                        new ViewExpiredException(
                                "The second sign-in view expired.", "/login.xhtml")));
        ExceptionQueuedEvent nonrecoverableEvent =
                event(facesContext, new IllegalStateException("Database failed."));
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(
                firstRecoverableEvent, nonrecoverableEvent, secondRecoverableEvent);

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        List<ExceptionQueuedEvent> expectedDelegatedEvents = List.of(nonrecoverableEvent);
        assertAll(
                () -> assertIterableEquals(
                        expectedDelegatedEvents,
                        wrappedHandler.getUnhandledExceptionQueuedEvents()),
                () -> assertIterableEquals(
                        expectedDelegatedEvents, wrappedHandler.eventsObservedDuringHandle()),
                () -> assertEquals(1, wrappedHandler.handleCallCount()),
                () -> assertEquals(1, responseRecorder.resetBufferCallCount()),
                () -> assertEquals(1, responseRecorder.statusWriteCount()),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, responseRecorder.headerWriteCount("Location")),
                () -> assertEquals(
                        "/calendar/login?reauthenticationRequired=true",
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(1, responseRecorder.headerWriteCount("Cache-Control")),
                () -> assertEquals(
                        "no-store", responseRecorder.lastHeaderValue("Cache-Control")),
                () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    @Test
    void expiredInvitationAwareSignInAjaxPostbackPreservesOneValidatedContinuation() {
        String invitationToken = "alpha beta&gamma" + "A".repeat(27);
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared/login",
                        Map.of(
                                "signInForm:invitationToken",
                                new String[] {invitationToken}),
                        Map.of(),
                        Map.of("Faces-Request", "partial/ajax"),
                        null),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException("The invitation-aware sign-in view expired.", "/login.xhtml")));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(HttpServletResponse.SC_OK, responseRecorder.status()),
                () -> assertEquals(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<partial-response><redirect url=\"/shared/login?"
                                + "reauthenticationRequired=true&amp;token=alpha+beta%26gamma"
                                + "A".repeat(27)
                                + "\"/></partial-response>",
                        responseRecorder.responseBody()),
                () -> assertEquals(0, responseRecorder.headerWriteCount("Location")),
                () -> assertTrue(facesContext.messages().isEmpty()),
                () -> assertFalse(facesContext.flash().isKeepMessages()),
                () -> assertFalse(facesContext.flash().isRedirect()),
                () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    @Test
    void signInInvitationRecoveryRejectsDuplicateConflictingAndMalformedContinuations() {
        String invitationToken = "A".repeat(43);
        ViewExpiredException expiredSignInView =
                new ViewExpiredException("The sign-in view expired.", "/login.xhtml");

        assertAll(
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandler.RECOVERY_ROUTE + "&token=" + invitationToken,
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        "/login",
                                        Map.of("invite", new String[] {invitationToken}),
                                        Map.of(),
                                        Map.of(),
                                        null),
                                response(false),
                                expiredSignInView)),
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandler.RECOVERY_ROUTE + "&token=" + invitationToken,
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        "/login",
                                        Map.of("token", new String[] {invitationToken}),
                                        Map.of(),
                                        Map.of(),
                                        null),
                                response(false),
                                expiredSignInView)),
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandler.RECOVERY_ROUTE,
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        "/login",
                                        Map.of(
                                                "token", new String[] {invitationToken},
                                                "form:invitationToken", new String[] {invitationToken}),
                                        Map.of(),
                                        Map.of(),
                                        null),
                                response(false),
                                expiredSignInView)),
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandler.RECOVERY_ROUTE,
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        "/login",
                                        Map.of(
                                                "invite", new String[] {invitationToken},
                                                "form:invitationToken", new String[] {"B".repeat(43)}),
                                        Map.of(),
                                        Map.of(),
                                        null),
                                response(false),
                                expiredSignInView)),
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandler.RECOVERY_ROUTE,
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        "/login",
                                        Map.of(
                                                "form:invitationToken",
                                                new String[] {invitationToken, invitationToken}),
                                        Map.of(),
                                        Map.of(),
                                        null),
                                response(false),
                                expiredSignInView)),
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandler.RECOVERY_ROUTE,
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        "/login",
                                        Map.of(
                                                "form:invitationToken",
                                                new String[] {"A".repeat(81)}),
                                        Map.of(),
                                        Map.of(),
                                        null),
                                response(false),
                                expiredSignInView)));
    }

    @Test
    void expiredRegistrationPostbackPreservesOnlyOneValidatedInvitationDestination() {
        String invitationToken = "alpha beta&gamma" + "A".repeat(27);
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared/register",
                        Map.of(
                                "registrationForm:invitationToken",
                                new String[] {invitationToken}),
                        Map.of(),
                        Map.of(),
                        null),
                responseRecorder.response(),
                "/shared");
        ExceptionQueuedEvent registrationEvent = event(
                facesContext,
                new ViewExpiredException("The registration view expired.", "/register.xhtml"));
        RecordingExceptionHandler wrappedHandler =
                new RecordingExceptionHandler(registrationEvent);

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(
                        "/shared/register?token=alpha+beta%26gamma" + "A".repeat(27),
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertEquals(
                        FacesMessage.SEVERITY_WARN,
                        facesContext.messages().getFirst().getSeverity()),
                () -> assertEquals(
                        "Page refreshed.",
                        facesContext.messages().getFirst().getSummary()),
                () -> assertFalse(
                        facesContext.messages().getFirst().getDetail().contains(invitationToken)),
                () -> assertTrue(facesContext.flash().isKeepMessages()),
                () -> assertTrue(facesContext.flash().isRedirect()));
    }

    @Test
    void expiredAnonymousCalendarAjaxPostbackRedirectsToAFreshCanonicalGet() {
        String calendarLinkToken = "CurrentAbc0";
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared/" + calendarLinkToken,
                        Map.of(),
                        Map.of(),
                        Map.of("Faces-Request", "partial/ajax"),
                        null),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException("The calendar view expired.", "/calendar.xhtml")));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(HttpServletResponse.SC_OK, responseRecorder.status()),
                () -> assertEquals(
                        "text/xml;charset=UTF-8",
                        responseRecorder.contentType()),
                () -> assertEquals(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<partial-response><redirect url=\"/shared/"
                                + calendarLinkToken
                                + "\"/></partial-response>",
                        responseRecorder.responseBody()),
                () -> assertEquals(0, responseRecorder.headerWriteCount("Location")),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertTrue(facesContext.flash().isKeepMessages()),
                () -> assertTrue(facesContext.flash().isRedirect()));
    }

    @Test
    void expiredSignedInCalendarFormUsesMatchingTrustedForwardState() {
        String calendarLinkToken = "CurrentAbc0";
        Principal signedInUser = () -> "calendar-member";
        Map<String, Object> requestAttributes = Map.of(
                CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE,
                calendarLinkToken,
                RequestDispatcher.FORWARD_REQUEST_URI,
                "/shared/" + calendarLinkToken);
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared/calendar.xhtml",
                        Map.of(),
                        requestAttributes,
                        Map.of(),
                        signedInUser),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException("The calendar form expired.", "/calendar.xhtml")));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(
                        "/shared/" + calendarLinkToken,
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, facesContext.messages().size()),
                        () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("homeSignOutPostbacks")
    void expiredHomeNavigationSignOutPostbackRecoversTheCanonicalFreshGet(
            String scenario,
            String requestPath,
            String viewId) {
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared" + requestPath,
                        Map.of(
                                "navigationForm:signOutButton",
                                new String[] {"Sign out"}),
                        Map.of(),
                        Map.of(),
                        () -> "calendar-member"),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException("The signed-in home view expired.", viewId)));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(
                        "/shared/",
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertTrue(facesContext.flash().isKeepMessages()),
                () -> assertTrue(facesContext.flash().isRedirect()),
                () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    private static Stream<Arguments> homeSignOutPostbacks() {
        return Stream.of(
                Arguments.of("canonical home route", "/", "/index.xhtml"),
                Arguments.of("extensionless index route", "/index", "/index"),
                Arguments.of("internal index template route", "/index.xhtml", "/index.xhtml"));
    }

    @Test
    void expiredHomeSignOutPostbackRecoversAfterItsAuthenticatedSessionWasLost() {
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared/index.xhtml",
                        Map.of(
                                "navigationForm:signOutButton",
                                new String[] {"Sign out"}),
                        Map.of(),
                        Map.of(),
                        null),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException(
                        "The signed-in home view expired with its session.",
                        "/index.xhtml")));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(
                        "/shared/",
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertTrue(facesContext.flash().isKeepMessages()),
                () -> assertTrue(facesContext.flash().isRedirect()),
                () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticatedSharedNavigationPostbacks")
    void expiredSharedNavigationSignOutPostbackRecoversTheAllowlistedFreshGet(
            String scenario,
            String applicationRoute,
            String viewId,
            Map<String, String[]> requestParameters,
            String expectedRecoveryRoute) {
        Principal signedInUser = () -> "calendar-member";
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared" + applicationRoute,
                        requestParameters,
                        Map.of(),
                        Map.of(),
                        signedInUser),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException("The signed-in view expired.", viewId)));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(
                        "/shared" + expectedRecoveryRoute,
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertEquals(
                        "Page refreshed.",
                        facesContext.messages().getFirst().getSummary()),
                () -> assertTrue(facesContext.flash().isKeepMessages()),
                () -> assertTrue(facesContext.flash().isRedirect()),
                () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    private static Stream<Arguments> authenticatedSharedNavigationPostbacks() {
        Map<String, String[]> signOutPostback = Map.of(
                "navigationForm:signOutButton", new String[] {"Sign out"});
        return Stream.of(
                Arguments.of(
                        "calendar list sign-out",
                        "/app/calendars",
                        "/app/calendars.xhtml",
                        signOutPostback,
                        "/app/calendars"),
                Arguments.of(
                        "invitation list sign-out",
                        "/app/invitations.xhtml",
                        "/app/invitations",
                        signOutPostback,
                        "/app/invitations"),
                Arguments.of(
                        "calendar settings sign-out",
                        "/app/calendar-settings",
                        "/app/calendar-settings.xhtml",
                        signOutPostback,
                        "/app/calendars"),
                Arguments.of(
                        "calendar members sign-out",
                        "/app/calendar-members.xhtml",
                        "/app/calendar-members",
                        signOutPostback,
                        "/app/calendars"),
                Arguments.of(
                        "account settings sign-out",
                        "/app/account-settings",
                        "/app/account-settings.xhtml",
                        signOutPostback,
                        "/app/account-settings"));
    }

    @Test
    void expiredAuthenticatedCalendarAdministrationAjaxPostbackUsesAFreshValidatedGet() {
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request(
                        "POST",
                        "/shared",
                        "/shared/app/calendar-settings",
                        Map.of("id", new String[] {"42"}),
                        Map.of(),
                        Map.of("Faces-Request", "partial/ajax"),
                        () -> "calendar-member"),
                responseRecorder.response(),
                "/shared");
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(event(
                facesContext,
                new ViewExpiredException(
                        "The calendar settings view expired.",
                        "/app/calendar-settings.xhtml")));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertTrue(wrappedHandler.unhandledEvents.isEmpty()),
                () -> assertEquals(HttpServletResponse.SC_OK, responseRecorder.status()),
                () -> assertEquals(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<partial-response><redirect url=\"/shared/app/calendar-settings?id=42\"/>"
                                + "</partial-response>",
                        responseRecorder.responseBody()),
                () -> assertEquals(0, responseRecorder.headerWriteCount("Location")),
                () -> assertEquals(1, facesContext.messages().size()),
                () -> assertTrue(facesContext.flash().isKeepMessages()),
                () -> assertTrue(facesContext.flash().isRedirect()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("calendarAdministrationViews")
    void calendarAdministrationRecoveryPreservesOnlyOneValidatedCalendarId(
            String scenario,
            String applicationRoute,
            String viewId) {
        Principal signedInUser = () -> "calendar-member";
        ViewExpiredException expiredView =
                new ViewExpiredException("The calendar administration view expired.", viewId);

        assertAll(
                () -> assertEquals(
                        applicationRoute + "?id=42",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        applicationRoute,
                                        Map.of("id", new String[] {"00042"}),
                                        Map.of(),
                                        Map.of(),
                                        signedInUser),
                                response(false),
                                expiredView)),
                () -> assertEquals(
                        "/app/calendars",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        applicationRoute,
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        signedInUser),
                                response(false),
                                expiredView)),
                () -> assertEquals(
                        "/app/calendars",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        applicationRoute,
                                        Map.of("id", new String[] {"42", "84"}),
                                        Map.of(),
                                        Map.of(),
                                        signedInUser),
                                response(false),
                                expiredView)),
                () -> assertEquals(
                        "/app/calendars",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                request(
                                        "POST",
                                        "",
                                        applicationRoute,
                                        Map.of("id", new String[] {"//attacker.example"}),
                                        Map.of(),
                                        Map.of(),
                                        signedInUser),
                                response(false),
                                expiredView)));
    }

    private static Stream<Arguments> calendarAdministrationViews() {
        return Stream.of(
                Arguments.of(
                        "calendar settings",
                        "/app/calendar-settings",
                        "/app/calendar-settings.xhtml"),
                Arguments.of(
                        "calendar members",
                        "/app/calendar-members",
                        "/app/calendar-members.xhtml"));
    }

    @Test
    void malformedOrConflictingRecoveryDestinationsFallBackWithoutReflection() {
        String firstInvitationToken = "A".repeat(43);
        String secondInvitationToken = "B".repeat(43);
        HttpServletRequest conflictingRegistrationRequest = request(
                "POST",
                "/shared",
                "/shared/register",
                Map.of(
                        "token", new String[] {firstInvitationToken},
                        "form:invitationToken", new String[] {secondInvitationToken}),
                Map.of(),
                Map.of(),
                null);
        HttpServletRequest duplicateRegistrationRequest = request(
                "POST",
                "/shared",
                "/shared/register",
                Map.of(
                        "token", new String[] {firstInvitationToken},
                        "form:invitationToken", new String[] {firstInvitationToken}),
                Map.of(),
                Map.of(),
                null);
        HttpServletRequest malformedSignedInCalendarRequest = request(
                "POST",
                "/shared",
                "/shared/calendar.xhtml",
                Map.of(),
                Map.of(
                        CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE,
                        "//attacker.example"),
                Map.of(),
                () -> "calendar-member");
        HttpServletRequest conflictingAnonymousCalendarRequest = request(
                "POST",
                "/shared",
                "/shared/CurrentAbc0",
                Map.of(),
                Map.of(
                        CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE,
                        "OtherLinkA0"),
                Map.of(),
                null);
        ViewExpiredException registrationException =
                new ViewExpiredException("The registration view expired.", "/register.xhtml");
        ViewExpiredException calendarException =
                new ViewExpiredException("The calendar view expired.", "/calendar.xhtml");

        assertAll(
                () -> assertEquals(
                        "/",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                conflictingRegistrationRequest,
                                response(false),
                                registrationException)),
                () -> assertEquals(
                        "/",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                duplicateRegistrationRequest,
                                response(false),
                                registrationException)),
                () -> assertEquals(
                        "/app/calendars",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                malformedSignedInCalendarRequest,
                                response(false),
                                calendarException)),
                () -> assertEquals(
                        "/",
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                conflictingAnonymousCalendarRequest,
                                response(false),
                                calendarException)),
                () -> assertFalse(
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                        conflictingRegistrationRequest,
                                        response(false),
                                        registrationException)
                                .contains(firstInvitationToken)),
                () -> assertFalse(
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                        malformedSignedInCalendarRequest,
                                        response(false),
                                        calendarException)
                                .contains("attacker.example")),
                () -> assertFalse(
                        ExpiredSignInViewExceptionHandler.recoveryRouteForExpiredPostback(
                                        conflictingAnonymousCalendarRequest,
                                        response(false),
                                        calendarException)
                                .contains("CurrentAbc0")));
    }

    @Test
    void handleLeavesNonrecoverableEventsForTheWrappedHandler() {
        ResponseRecorder getResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext getFacesContext = facesContext(
                request("GET", "", "/login"), getResponseRecorder.response(), "");
        ExceptionQueuedEvent getEvent = event(
                getFacesContext,
                new ViewExpiredException("A non-postback view expired.", "/login"));

        ResponseRecorder committedResponseRecorder = new ResponseRecorder(true);
        RecordingFacesContext committedFacesContext = facesContext(
                request("POST", "", "/login"), committedResponseRecorder.response(), "");
        ExceptionQueuedEvent committedEvent = event(
                committedFacesContext,
                new ViewExpiredException("A committed view expired.", "/login"));

        ResponseRecorder otherViewResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext otherViewFacesContext = facesContext(
                request("POST", "", "/app/calendars"), otherViewResponseRecorder.response(), "");
        ExceptionQueuedEvent otherViewEvent = event(
                otherViewFacesContext,
                new ViewExpiredException("The calendars view expired.", "/app/calendars.xhtml"));

        ResponseRecorder unlistedAuthenticatedViewResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext unlistedAuthenticatedViewFacesContext = facesContext(
                request(
                        "POST",
                        "",
                        "/app/administration",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        () -> "calendar-member"),
                unlistedAuthenticatedViewResponseRecorder.response(),
                "");
        ExceptionQueuedEvent unlistedAuthenticatedViewEvent = event(
                unlistedAuthenticatedViewFacesContext,
                new ViewExpiredException(
                        "An unlisted authenticated view expired.",
                        "/app/administration.xhtml"));

        ResponseRecorder mismatchedAuthenticatedViewResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext mismatchedAuthenticatedViewFacesContext = facesContext(
                request(
                        "POST",
                        "",
                        "/app/invitations",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        () -> "calendar-member"),
                mismatchedAuthenticatedViewResponseRecorder.response(),
                "");
        ExceptionQueuedEvent mismatchedAuthenticatedViewEvent = event(
                mismatchedAuthenticatedViewFacesContext,
                new ViewExpiredException(
                        "A mismatched authenticated view expired.",
                        "/app/account-settings.xhtml"));

        ResponseRecorder inexactHomeRouteResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext inexactHomeRouteFacesContext = facesContext(
                request(
                        "POST",
                        "",
                        "/index/extra",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        () -> "calendar-member"),
                inexactHomeRouteResponseRecorder.response(),
                "");
        ExceptionQueuedEvent inexactHomeRouteEvent = event(
                inexactHomeRouteFacesContext,
                new ViewExpiredException(
                        "An inexact home view route expired.",
                        "/index.xhtml"));

        List<ExceptionQueuedEvent> nonrecoverableEvents =
                List.of(
                        getEvent,
                        committedEvent,
                        otherViewEvent,
                        unlistedAuthenticatedViewEvent,
                        mismatchedAuthenticatedViewEvent,
                        inexactHomeRouteEvent);
        RecordingExceptionHandler wrappedHandler =
                new RecordingExceptionHandler(nonrecoverableEvents.toArray(ExceptionQueuedEvent[]::new));

        new ExpiredSignInViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertIterableEquals(
                        nonrecoverableEvents,
                        wrappedHandler.getUnhandledExceptionQueuedEvents()),
                () -> assertIterableEquals(
                        nonrecoverableEvents, wrappedHandler.eventsObservedDuringHandle()),
                () -> assertEquals(1, wrappedHandler.handleCallCount()),
                () -> assertEquals(0, getResponseRecorder.responseWriteCount()),
                () -> assertEquals(0, committedResponseRecorder.responseWriteCount()),
                () -> assertEquals(0, otherViewResponseRecorder.responseWriteCount()),
                () -> assertEquals(
                        0,
                        unlistedAuthenticatedViewResponseRecorder.responseWriteCount()),
                () -> assertEquals(
                        0,
                        mismatchedAuthenticatedViewResponseRecorder.responseWriteCount()),
                () -> assertEquals(
                        0,
                        inexactHomeRouteResponseRecorder.responseWriteCount()),
                () -> assertEquals(0, getFacesContext.responseCompleteCallCount()),
                () -> assertEquals(0, committedFacesContext.responseCompleteCallCount()),
                () -> assertEquals(0, otherViewFacesContext.responseCompleteCallCount()),
                () -> assertEquals(
                        0,
                        unlistedAuthenticatedViewFacesContext.responseCompleteCallCount()),
                () -> assertEquals(
                        0,
                        mismatchedAuthenticatedViewFacesContext.responseCompleteCallCount()),
                () -> assertEquals(
                        0,
                        inexactHomeRouteFacesContext.responseCompleteCallCount()));
    }

    @Test
    void recoversOnlyUncommittedExpiredSignInPostbacks() {
        Throwable wrappedExpiredSignInView = new ServletException(
                "Faces postback failed.",
                new ViewExpiredException("The view expired.", "/login"));

        assertAll(
                () -> assertTrue(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("POST", "", "/login"),
                        response(false),
                        wrappedExpiredSignInView)),
                () -> assertTrue(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("POST", "/calendar", "/calendar/login.xhtml"),
                        response(false),
                        new ViewExpiredException("The view expired.", "/login.xhtml"))),
                () -> assertTrue(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request(
                                "POST",
                                "",
                                "/login",
                                Map.of(
                                        "form:invitationToken",
                                        new String[] {"A".repeat(43)}),
                                Map.of(),
                                Map.of(),
                                null),
                        response(false),
                        new ViewExpiredException("The invitation sign-in view expired.", "/login"))),
                () -> assertFalse(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("GET", "", "/login"),
                        response(false),
                        wrappedExpiredSignInView)),
                () -> assertFalse(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("POST", "", "/register"),
                        response(false),
                        wrappedExpiredSignInView)),
                () -> assertFalse(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("POST", "", "/login"),
                        response(true),
                        wrappedExpiredSignInView)),
                () -> assertFalse(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("POST", "", "/login"),
                        response(false),
                        new ViewExpiredException("Another view expired.", "/register"))),
                () -> assertFalse(ExpiredSignInViewExceptionHandler.isRecoverableExpiredSignInPostback(
                        request("POST", "", "/login"),
                        response(false),
                        new IllegalStateException("Database failed."))));
    }

    @Test
    void facesConfigurationRegistersTheRecoveryFactory() throws Exception {
        NodeList factoryElements = parseXml(FACES_CONFIGURATION_PATH)
                .getElementsByTagName("exception-handler-factory");

        assertAll(
                () -> assertEquals(1, factoryElements.getLength()),
                () -> assertEquals(
                        ExpiredSignInViewExceptionHandlerFactory.class.getName(),
                        factoryElements.item(0).getTextContent().trim()));
    }

    @Test
    void everyRegistrationFormSubmitsTheStableInvitationTokenParameter() throws Exception {
        NodeList registrationForms = parseXml(REGISTRATION_VIEW_PATH)
                .getElementsByTagName("h:form");

        assertEquals(2, registrationForms.getLength());
        for (int formIndex = 0; formIndex < registrationForms.getLength(); formIndex++) {
            Element registrationForm = (Element) registrationForms.item(formIndex);
            NodeList hiddenInputs = registrationForm.getElementsByTagName("h:inputHidden");
            int invitationTokenInputCount = 0;
            for (int inputIndex = 0; inputIndex < hiddenInputs.getLength(); inputIndex++) {
                Element hiddenInput = (Element) hiddenInputs.item(inputIndex);
                if ("invitationToken".equals(hiddenInput.getAttribute("id"))
                        && "#{registrationView.invitationToken}"
                                .equals(hiddenInput.getAttribute("value"))) {
                    invitationTokenInputCount++;
                }
            }
            assertEquals(
                    1,
                    invitationTokenInputCount,
                    "Every registration form must submit one recognizable invitation token.");
        }
    }

    @Test
    void invitationAwareSignInFormSubmitsTheStableInvitationTokenParameter() throws Exception {
        NodeList signInForms = parseXml(SIGN_IN_VIEW_PATH).getElementsByTagName("h:form");

        assertEquals(1, signInForms.getLength());
        Element signInForm = (Element) signInForms.item(0);
        NodeList hiddenInputs = signInForm.getElementsByTagName("h:inputHidden");
        int invitationTokenInputCount = 0;
        for (int inputIndex = 0; inputIndex < hiddenInputs.getLength(); inputIndex++) {
            Element hiddenInput = (Element) hiddenInputs.item(inputIndex);
            if ("invitationToken".equals(hiddenInput.getAttribute("id"))
                    && "#{signInView.invitationToken}"
                            .equals(hiddenInput.getAttribute("value"))) {
                invitationTokenInputCount++;
            }
        }
        assertEquals(
                1,
                invitationTokenInputCount,
                "The sign-in form must submit one recognizable invitation continuation token.");
    }

    private static HttpServletRequest request(
            String method,
            String contextPath,
            String requestUri) {
        return request(
                method,
                contextPath,
                requestUri,
                Map.of(),
                Map.of(),
                Map.of(),
                null);
    }

    private static HttpServletRequest request(
            String method,
            String contextPath,
            String requestUri,
            Map<String, String[]> requestParameters,
            Map<String, Object> requestAttributes,
            Map<String, String> requestHeaders,
            Principal userPrincipal) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (ignoredProxy, invokedMethod, arguments) -> switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getContextPath" -> contextPath;
                    case "getRequestURI" -> requestUri;
                    case "getParameterMap" -> requestParameters;
                    case "getParameterValues" -> requestParameters.get((String) arguments[0]);
                    case "getParameter" -> firstValue(requestParameters.get((String) arguments[0]));
                    case "getAttribute" -> requestAttributes.get((String) arguments[0]);
                    case "getHeader" -> requestHeaders.get((String) arguments[0]);
                    case "getUserPrincipal" -> userPrincipal;
                    default -> defaultValue(invokedMethod.getReturnType());
                });
    }

    private static String firstValue(String[] values) {
        return values == null || values.length == 0 ? null : values[0];
    }

    private static HttpServletResponse response(boolean committed) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (ignoredProxy, invokedMethod, ignoredArguments) ->
                        invokedMethod.getName().equals("isCommitted")
                                ? committed
                                : defaultValue(invokedMethod.getReturnType()));
    }

    private static RecordingFacesContext facesContext(
            HttpServletRequest request,
            HttpServletResponse response,
            String requestContextPath) {
        return new RecordingFacesContext(
                new RecordingExternalContext(request, response, requestContextPath));
    }

    private static ExceptionQueuedEvent event(FacesContext facesContext, Throwable exception) {
        return new ExceptionQueuedEvent(new ExceptionQueuedEventContext(facesContext, exception));
    }

    private static final class RecordingExceptionHandler extends ExceptionHandler {
        private final List<ExceptionQueuedEvent> unhandledEvents;
        private List<ExceptionQueuedEvent> eventsObservedDuringHandle = List.of();
        private int handleCallCount;

        private RecordingExceptionHandler(ExceptionQueuedEvent... unhandledEvents) {
            this.unhandledEvents = new ArrayList<>(List.of(unhandledEvents));
        }

        @Override
        public void handle() throws FacesException {
            handleCallCount++;
            eventsObservedDuringHandle = List.copyOf(unhandledEvents);
        }

        @Override
        public ExceptionQueuedEvent getHandledExceptionQueuedEvent() {
            return null;
        }

        @Override
        public Iterable<ExceptionQueuedEvent> getUnhandledExceptionQueuedEvents() {
            return unhandledEvents;
        }

        @Override
        public Iterable<ExceptionQueuedEvent> getHandledExceptionQueuedEvents() {
            return List.of();
        }

        @Override
        public void processEvent(SystemEvent event) throws AbortProcessingException {
            // The recovery handler does not publish events to its wrapped handler.
        }

        @Override
        public boolean isListenerForSource(Object source) {
            return false;
        }

        @Override
        public Throwable getRootCause(Throwable throwable) {
            return throwable;
        }

        private List<ExceptionQueuedEvent> eventsObservedDuringHandle() {
            return eventsObservedDuringHandle;
        }

        private int handleCallCount() {
            return handleCallCount;
        }
    }

    private static final class RecordingFacesContext extends FacesContextWrapper {
        private final RecordingExternalContext externalContext;
        private final List<FacesMessage> messages = new ArrayList<>();
        private int responseCompleteCallCount;

        private RecordingFacesContext(RecordingExternalContext externalContext) {
            super(null);
            this.externalContext = externalContext;
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
        public PhaseId getCurrentPhaseId() {
            return PhaseId.ANY_PHASE;
        }

        @Override
        public void addMessage(String clientId, FacesMessage message) {
            messages.add(message);
        }

        @Override
        public void responseComplete() {
            responseCompleteCallCount++;
        }

        private List<FacesMessage> messages() {
            return messages;
        }

        private RecordingFlash flash() {
            return externalContext.flash();
        }

        private int responseCompleteCallCount() {
            return responseCompleteCallCount;
        }
    }

    private static final class RecordingExternalContext extends ExternalContextWrapper {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String requestContextPath;
        private final RecordingFlash flash = new RecordingFlash();

        private RecordingExternalContext(
                HttpServletRequest request,
                HttpServletResponse response,
                String requestContextPath) {
            super(null);
            this.request = request;
            this.response = response;
            this.requestContextPath = requestContextPath;
        }

        @Override
        public ExternalContext getWrapped() {
            return null;
        }

        @Override
        public Object getRequest() {
            return request;
        }

        @Override
        public Object getResponse() {
            return response;
        }

        @Override
        public Map<String, String> getInitParameterMap() {
            return Map.of();
        }

        @Override
        public Flash getFlash() {
            return flash;
        }

        @Override
        public String getRequestContextPath() {
            return requestContextPath;
        }

        private RecordingFlash flash() {
            return flash;
        }
    }

    private static final class RecordingFlash extends Flash {
        private final Map<String, Object> values = new HashMap<>();
        private boolean keepMessages;
        private boolean redirect;

        @Override
        public boolean isKeepMessages() {
            return keepMessages;
        }

        @Override
        public void setKeepMessages(boolean keepMessages) {
            this.keepMessages = keepMessages;
        }

        @Override
        public boolean isRedirect() {
            return redirect;
        }

        @Override
        public void setRedirect(boolean redirect) {
            this.redirect = redirect;
        }

        @Override
        public void putNow(String key, Object value) {
            values.put(key, value);
        }

        @Override
        public void keep(String key) {
            // The tests only verify message and redirect retention flags.
        }

        @Override
        public void doPrePhaseActions(FacesContext facesContext) {
            // No JSF lifecycle is run in this focused handler test.
        }

        @Override
        public void doPostPhaseActions(FacesContext facesContext) {
            // No JSF lifecycle is run in this focused handler test.
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return values.containsValue(value);
        }

        @Override
        public Object get(Object key) {
            return values.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            return values.put(key, value);
        }

        @Override
        public Object remove(Object key) {
            return values.remove(key);
        }

        @Override
        public void putAll(Map<? extends String, ?> valuesToAdd) {
            values.putAll(valuesToAdd);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }

        @Override
        public Collection<Object> values() {
            return values.values();
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return values.entrySet();
        }
    }

    private static final class ResponseRecorder {
        private final List<HeaderWrite> headerWrites = new ArrayList<>();
        private final StringWriter responseBody = new StringWriter();
        private final HttpServletResponse response;
        private final PrintWriter responseWriter = new PrintWriter(responseBody);
        private int resetBufferCallCount;
        private int statusWriteCount;
        private int status;
        private String contentType;

        private ResponseRecorder(boolean committed) {
            response = (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class},
                    (ignoredProxy, invokedMethod, arguments) -> {
                        switch (invokedMethod.getName()) {
                            case "isCommitted" -> {
                                return committed;
                            }
                            case "resetBuffer" -> resetBufferCallCount++;
                            case "setStatus" -> {
                                statusWriteCount++;
                                status = (int) arguments[0];
                            }
                            case "setHeader" -> headerWrites.add(
                                    new HeaderWrite((String) arguments[0], (String) arguments[1]));
                            case "setContentType" -> contentType = (String) arguments[0];
                            case "getWriter" -> {
                                return responseWriter;
                            }
                            default -> {
                                return defaultValue(invokedMethod.getReturnType());
                            }
                        }
                        return null;
                    });
        }

        private HttpServletResponse response() {
            return response;
        }

        private int resetBufferCallCount() {
            return resetBufferCallCount;
        }

        private int statusWriteCount() {
            return statusWriteCount;
        }

        private int status() {
            return status;
        }

        private String contentType() {
            return contentType;
        }

        private String responseBody() {
            responseWriter.flush();
            return responseBody.toString();
        }

        private int headerWriteCount(String headerName) {
            return Math.toIntExact(headerWrites.stream()
                    .filter(headerWrite -> headerWrite.name().equals(headerName))
                    .count());
        }

        private String lastHeaderValue(String headerName) {
            return headerWrites.stream()
                    .filter(headerWrite -> headerWrite.name().equals(headerName))
                    .reduce((firstHeaderWrite, secondHeaderWrite) -> secondHeaderWrite)
                    .map(HeaderWrite::value)
                    .orElse(null);
        }

        private int responseWriteCount() {
            return resetBufferCallCount + statusWriteCount + headerWrites.size();
        }
    }

    private record HeaderWrite(String name, String value) {}
}
