package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.calendar.CalendarRouteFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SessionCookieRefreshFilterTest {
    private static final String SESSION_ID = "session-id:server-clone";

    @Test
    void refreshesOnlyTheAuthenticatedValidSessionCookieForThirtyDays() throws Exception {
        SessionCookieRefreshFilter filter = new SessionCookieRefreshFilter(currentUser(true));
        List<Cookie> responseCookies = new ArrayList<>();
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter.doFilter(
                request(true, true, false, new Cookie("unrelated", "value"), new Cookie("JSESSIONID", SESSION_ID)),
                response(responseCookies, false),
                filterChain(filterChainCalls));

        Cookie refreshedSessionCookie = responseCookies.getFirst();
        assertAll(
                () -> assertEquals(1, filterChainCalls.get()),
                () -> assertEquals(1, responseCookies.size()),
                () -> assertEquals("JSESSIONID", refreshedSessionCookie.getName()),
                () -> assertEquals(SESSION_ID, refreshedSessionCookie.getValue()),
                () -> assertEquals("/", refreshedSessionCookie.getPath()),
                () -> assertTrue(refreshedSessionCookie.isHttpOnly()),
                () -> assertTrue(refreshedSessionCookie.getSecure()),
                () -> assertEquals(
                        SessionCookieRefreshFilter.SESSION_COOKIE_LIFETIME_SECONDS,
                        refreshedSessionCookie.getMaxAge()),
                () -> assertEquals("Lax", refreshedSessionCookie.getAttribute("SameSite")));
    }

    @Test
    void backendTransportCannotProduceAnInsecureRefreshedCookie() throws Exception {
        for (boolean secureBackendTransport : List.of(false, true)) {
            List<Cookie> responseCookies = new ArrayList<>();
            new SessionCookieRefreshFilter(currentUser(true))
                    .doFilter(
                            request(true, true, secureBackendTransport, new Cookie("JSESSIONID", SESSION_ID)),
                            response(responseCookies, false),
                            filterChain(new AtomicInteger()));
            assertTrue(responseCookies.getFirst().getSecure());
        }
    }

    @Test
    void doesNotRefreshAnonymousInvalidOrMissingSessions() throws Exception {
        List<TestCase> testCases = List.of(
                new TestCase(currentUser(false), request(true, false, false, new Cookie("JSESSIONID", SESSION_ID))),
                new TestCase(currentUser(true), request(false, true, false, new Cookie("JSESSIONID", SESSION_ID))),
                new TestCase(currentUser(true), request(true, false, false, new Cookie("JSESSIONID", SESSION_ID))),
                new TestCase(currentUser(true), request(true, true, false)),
                new TestCase(
                        currentUser(true),
                        request(
                                true,
                                true,
                                false,
                                new Cookie("JSESSIONID", SESSION_ID),
                                new Cookie("JSESSIONID", "ambiguous-session"))));

        for (TestCase testCase : testCases) {
            List<Cookie> responseCookies = new ArrayList<>();
            AtomicInteger filterChainCalls = new AtomicInteger();
            new SessionCookieRefreshFilter(testCase.currentUser())
                    .doFilter(testCase.request(), response(responseCookies, false), filterChain(filterChainCalls));
            assertAll(
                    () -> assertTrue(responseCookies.isEmpty()),
                    () -> assertEquals(1, filterChainCalls.get()));
        }
    }

    @Test
    void doesNotAskLibertyForSessionValidityAfterTheRequestBecomesAnonymous()
            throws Exception {
        AtomicInteger sessionValidityChecks = new AtomicInteger();
        AtomicInteger filterChainCalls = new AtomicInteger();
        HttpServletRequest anonymousRequest = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUserPrincipal" -> null;
                    case "isRequestedSessionIdValid" -> {
                        sessionValidityChecks.incrementAndGet();
                        throw new AssertionError(
                                "An anonymous request must not inspect a previously owned session.");
                    }
                    case "getCookies" -> new Cookie[] {new Cookie("JSESSIONID", SESSION_ID)};
                    case "getContextPath" -> "";
                    case "getRequestURI" -> "/app/calendars";
                    default -> defaultValue(method.getReturnType());
                });

        new SessionCookieRefreshFilter(currentUser(false))
                .doFilter(
                        anonymousRequest,
                        response(new ArrayList<>(), false),
                        filterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(1, filterChainCalls.get()),
                () -> assertEquals(0, sessionValidityChecks.get()));
    }

    @Test
    void expiresARejectedSingleSignOnCookieWithoutDiscardingTheAnonymousFacesSession()
            throws Exception {
        for (String requestUri : List.of("/", "/login", "/app/calendars")) {
            List<Cookie> responseCookies = new ArrayList<>();
            AtomicInteger filterChainCalls = new AtomicInteger();

            new SessionCookieRefreshFilter(currentUser(false))
                    .doFilter(
                            requestAtPath(
                                    requestUri,
                                    true,
                                    false,
                                    false,
                                    new Cookie("JSESSIONID", SESSION_ID),
                                    new Cookie("LtpaToken2", "rejected-token")),
                            response(responseCookies, false),
                            filterChain(filterChainCalls));

            Cookie expiredSingleSignOnCookie = responseCookies.getFirst();
            assertAll(
                    () -> assertEquals(1, filterChainCalls.get()),
                    () -> assertEquals(1, responseCookies.size()),
                    () -> assertEquals("LtpaToken2", expiredSingleSignOnCookie.getName()),
                    () -> assertTrue(expiredSingleSignOnCookie.getValue().isEmpty()),
                    () -> assertEquals("/", expiredSingleSignOnCookie.getPath()),
                    () -> assertTrue(expiredSingleSignOnCookie.isHttpOnly()),
                    () -> assertTrue(expiredSingleSignOnCookie.getSecure()),
                    () -> assertEquals(0, expiredSingleSignOnCookie.getMaxAge()),
                    () -> assertEquals("Lax", expiredSingleSignOnCookie.getAttribute("SameSite")));
        }
    }

    @Test
    void doesNotRefreshAfterLogoutOrAfterTheResponseIsCommitted() throws Exception {
        AtomicBoolean authenticated = new AtomicBoolean(true);
        List<Cookie> logoutResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true))
                .doFilter(
                        request(
                                true,
                                authenticated,
                                false,
                                new Cookie("JSESSIONID", SESSION_ID)),
                        response(logoutResponseCookies, false),
                        (request, response) -> authenticated.set(false));

        List<Cookie> committedResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true))
                .doFilter(
                        request(true, true, false, new Cookie("JSESSIONID", SESSION_ID)),
                        response(committedResponseCookies, true),
                        filterChain(new AtomicInteger()));

        assertAll(
                () -> assertTrue(logoutResponseCookies.isEmpty()),
                () -> assertTrue(committedResponseCookies.isEmpty()));
    }

    @Test
    void refreshesBeforeRenderingCanCommitButNotBeforeAnErrorResponse() throws Exception {
        AtomicBoolean renderingResponseCommitted = new AtomicBoolean();
        List<Cookie> renderingResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true))
                .doFilter(
                        request(true, true, false, new Cookie("JSESSIONID", SESSION_ID)),
                        response(renderingResponseCookies, renderingResponseCommitted),
                        (request, response) -> ((HttpServletResponse) response).getWriter());

        List<Cookie> errorResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true))
                .doFilter(
                        request(true, true, false, new Cookie("JSESSIONID", SESSION_ID)),
                        response(errorResponseCookies, false),
                        (request, response) -> ((HttpServletResponse) response).sendError(500));

        assertAll(
                () -> assertEquals(1, renderingResponseCookies.size()),
                () -> assertTrue(renderingResponseCommitted.get()),
                () -> assertTrue(errorResponseCookies.isEmpty()));
    }

    @Test
    void invalidatesStaleApplicationAndCalendarPostbackSessionsWithTheFixedLoginRedirect()
            throws Exception {
        for (String requestUri : List.of("/app/calendars", "/calendar.xhtml")) {
            StaleSessionCleanup sessionCleanup = new StaleSessionCleanup();
            AtomicReference<String> redirectLocation = new AtomicReference<>();
            AtomicInteger filterChainCalls = new AtomicInteger();
            List<Cookie> responseCookies = new ArrayList<>();

            new SessionCookieRefreshFilter(currentUser(false))
                    .doFilter(
                            staleSessionRequest(requestUri, sessionCleanup, null, "POST"),
                            redirectResponse(redirectLocation, responseCookies),
                            filterChain(filterChainCalls));

            assertAll(
                    () -> assertTrue(sessionCleanup.loggedOut.get()),
                    () -> assertTrue(sessionCleanup.sessionInvalidated.get()),
                    () -> assertEquals(List.of("logout", "session invalidated"), sessionCleanup.operations),
                    () -> assertEquals("/login?reauthenticationRequired=true", redirectLocation.get()),
                    () -> assertEquals(0, filterChainCalls.get()),
                    () -> assertExpiredSessionCookie(responseCookies));
        }
    }

    @Test
    void continuesStaleCanonicalCalendarRequestsAnonymouslyBeforeAndAfterForwarding() throws Exception {
        for (StaleCalendarRequest requestCase : List.of(
                new StaleCalendarRequest("/Abc_123-xY0", null),
                new StaleCalendarRequest("/calendar.xhtml", "Abc_123-xY0"))) {
            StaleSessionCleanup sessionCleanup = new StaleSessionCleanup();
            AtomicReference<String> redirectLocation = new AtomicReference<>();
            AtomicInteger filterChainCalls = new AtomicInteger();
            List<Cookie> responseCookies = new ArrayList<>();

            new SessionCookieRefreshFilter(currentUser(false))
                    .doFilter(
                            staleSessionRequest(
                                    requestCase.requestUri(),
                                    sessionCleanup,
                                    requestCase.forwardedCalendarLinkToken(),
                                    "GET"),
                            redirectResponse(redirectLocation, responseCookies),
                            filterChain(filterChainCalls));

            assertAll(
                    () -> assertTrue(sessionCleanup.loggedOut.get()),
                    () -> assertTrue(sessionCleanup.sessionInvalidated.get()),
                    () -> assertEquals(List.of("logout", "session invalidated"), sessionCleanup.operations),
                    () -> assertNull(redirectLocation.get()),
                    () -> assertEquals(1, filterChainCalls.get()),
                    () -> assertExpiredSessionCookie(responseCookies));
        }
    }

    private void assertExpiredSessionCookie(List<Cookie> responseCookies) {
        assertEquals(2, responseCookies.size());
        assertAll(
                () -> assertEquals(
                        List.of("JSESSIONID", "LtpaToken2"),
                        responseCookies.stream().map(Cookie::getName).toList()),
                () -> assertTrue(responseCookies.stream().allMatch(cookie -> cookie.getValue().isEmpty())),
                () -> assertTrue(responseCookies.stream().allMatch(cookie -> "/".equals(cookie.getPath()))),
                () -> assertTrue(responseCookies.stream().allMatch(Cookie::getSecure)),
                () -> assertTrue(responseCookies.stream().allMatch(Cookie::isHttpOnly)),
                () -> assertTrue(responseCookies.stream().allMatch(cookie -> cookie.getMaxAge() == 0)),
                () -> assertTrue(responseCookies.stream()
                        .allMatch(cookie -> "Lax".equals(cookie.getAttribute("SameSite")))));
    }

    private CurrentUser currentUser(boolean signedIn) {
        return new CurrentUser() {
            @Override
            public boolean isSignedIn() {
                return signedIn;
            }
        };
    }

    private HttpServletRequest request(
            boolean requestedSessionIdValid,
            boolean authenticated,
            boolean secure,
            Cookie... cookies) {
        return requestAtPath(
                "/app/calendars",
                requestedSessionIdValid,
                new AtomicBoolean(authenticated),
                secure,
                cookies);
    }

    private HttpServletRequest request(
            boolean requestedSessionIdValid,
            AtomicBoolean authenticated,
            boolean secure,
            Cookie... cookies) {
        return requestAtPath(
                "/app/calendars",
                requestedSessionIdValid,
                authenticated,
                secure,
                cookies);
    }

    private HttpServletRequest requestAtPath(
            String requestUri,
            boolean requestedSessionIdValid,
            boolean authenticated,
            boolean secure,
            Cookie... cookies) {
        return requestAtPath(
                requestUri,
                requestedSessionIdValid,
                new AtomicBoolean(authenticated),
                secure,
                cookies);
    }

    private HttpServletRequest requestAtPath(
            String requestUri,
            boolean requestedSessionIdValid,
            AtomicBoolean authenticated,
            boolean secure,
            Cookie... cookies) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getCookies" -> cookies.length == 0 ? null : cookies;
                    case "getRequestedSessionId" -> SESSION_ID;
                    case "getUserPrincipal" -> authenticated.get() ? (Principal) () -> "active-user" : null;
                    case "isRequestedSessionIdValid" -> requestedSessionIdValid;
                    case "isSecure" -> secure;
                    case "getContextPath" -> "";
                    case "getRequestURI" -> requestUri;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private HttpServletResponse response(List<Cookie> responseCookies, boolean committed) {
        return response(responseCookies, new AtomicBoolean(committed));
    }

    private HttpServletResponse response(
            List<Cookie> responseCookies,
            AtomicBoolean committed) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> {
                    if ("addCookie".equals(method.getName())) {
                        responseCookies.add((Cookie) arguments[0]);
                    }
                    if ("isCommitted".equals(method.getName())) {
                        return committed.get();
                    }
                    if ("getWriter".equals(method.getName())) {
                        committed.set(true);
                        return new PrintWriter(new StringWriter());
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private HttpServletRequest staleSessionRequest(
            String requestUri,
            StaleSessionCleanup sessionCleanup) {
        return staleSessionRequest(
                requestUri,
                sessionCleanup,
                null,
                "GET");
    }

    private HttpServletRequest staleSessionRequest(
            String requestUri,
            StaleSessionCleanup sessionCleanup,
            String forwardedCalendarLinkToken,
            String requestMethod) {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                HttpSession.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "invalidate" -> {
                        sessionCleanup.sessionInvalidated.set(true);
                        sessionCleanup.operations.add("session invalidated");
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUserPrincipal" -> (Principal) () -> "stale-user";
                    case "logout" -> {
                        sessionCleanup.loggedOut.set(true);
                        sessionCleanup.operations.add("logout");
                        yield null;
                    }
                    case "getSession" -> {
                        assertEquals(Boolean.FALSE, arguments[0]);
                        yield session;
                    }
                    case "getMethod" -> requestMethod;
                    case "getContextPath" -> "";
                    case "getRequestURI" -> requestUri;
                    case "getAttribute" ->
                            CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE.equals(arguments[0])
                                    ? forwardedCalendarLinkToken
                                    : null;
                    case "getQueryString" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private HttpServletResponse redirectResponse(
            AtomicReference<String> redirectLocation,
            List<Cookie> responseCookies) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setHeader")
                            && arguments[0].equals("Location")) {
                        redirectLocation.set((String) arguments[1]);
                        return null;
                    }
                    if (method.getName().equals("addCookie")) {
                        responseCookies.add((Cookie) arguments[0]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private FilterChain filterChain(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    private record TestCase(CurrentUser currentUser, HttpServletRequest request) {
    }

    private record StaleCalendarRequest(String requestUri, String forwardedCalendarLinkToken) {
    }

    private static final class StaleSessionCleanup {
        private final AtomicBoolean loggedOut = new AtomicBoolean();
        private final AtomicBoolean sessionInvalidated = new AtomicBoolean();
        private final List<String> operations = new ArrayList<>();
    }
}
