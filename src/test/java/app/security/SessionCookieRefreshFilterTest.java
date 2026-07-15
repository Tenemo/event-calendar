package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionCookieRefreshFilterTest {
    private static final String SESSION_ID = "session-id:server-clone";

    @Test
    void refreshesOnlyTheAuthenticatedValidSessionCookieForThirtyDays() throws Exception {
        SessionCookieRefreshFilter filter = new SessionCookieRefreshFilter(currentUser(true), false);
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
                () -> assertFalse(refreshedSessionCookie.getSecure()),
                () -> assertEquals(
                        SessionCookieRefreshFilter.SESSION_COOKIE_LIFETIME_SECONDS,
                        refreshedSessionCookie.getMaxAge()),
                () -> assertEquals("Lax", refreshedSessionCookie.getAttribute("SameSite")));
    }

    @Test
    void secureTransportAlwaysProducesASecureRefreshedCookie() throws Exception {
        for (boolean secureCookieConfiguration : List.of(false, true)) {
            List<Cookie> responseCookies = new ArrayList<>();
            new SessionCookieRefreshFilter(currentUser(true), secureCookieConfiguration)
                    .doFilter(
                            request(true, true, true, new Cookie("JSESSIONID", SESSION_ID)),
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
            new SessionCookieRefreshFilter(testCase.currentUser(), false)
                    .doFilter(testCase.request(), response(responseCookies, false), filterChain(filterChainCalls));
            assertAll(
                    () -> assertTrue(responseCookies.isEmpty()),
                    () -> assertEquals(1, filterChainCalls.get()));
        }
    }

    @Test
    void doesNotRefreshAfterLogoutOrAfterTheResponseIsCommitted() throws Exception {
        AtomicBoolean authenticated = new AtomicBoolean(true);
        List<Cookie> logoutResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true), true)
                .doFilter(
                        request(
                                true,
                                authenticated,
                                false,
                                new Cookie("JSESSIONID", SESSION_ID)),
                        response(logoutResponseCookies, false),
                        (request, response) -> authenticated.set(false));

        List<Cookie> committedResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true), true)
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
        new SessionCookieRefreshFilter(currentUser(true), false)
                .doFilter(
                        request(true, true, false, new Cookie("JSESSIONID", SESSION_ID)),
                        response(renderingResponseCookies, renderingResponseCommitted),
                        (request, response) -> ((HttpServletResponse) response).getWriter());

        List<Cookie> errorResponseCookies = new ArrayList<>();
        new SessionCookieRefreshFilter(currentUser(true), false)
                .doFilter(
                        request(true, true, false, new Cookie("JSESSIONID", SESSION_ID)),
                        response(errorResponseCookies, false),
                        (request, response) -> ((HttpServletResponse) response).sendError(500));

        assertAll(
                () -> assertEquals(1, renderingResponseCookies.size()),
                () -> assertTrue(renderingResponseCommitted.get()),
                () -> assertTrue(errorResponseCookies.isEmpty()));
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
        return request(requestedSessionIdValid, new AtomicBoolean(authenticated), secure, cookies);
    }

    private HttpServletRequest request(
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
}
