package app.security;

import static app.testsupport.ProxyReturnValues.createInterfaceProxy;
import static app.testsupport.ProxyReturnValues.defaultValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.user.ApplicationUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AuthenticatedSessionSecurityTest {
    @Test
    void logsOutBeforeInvalidatingAnySessionThatTheContainerRetains() throws Exception {
        AtomicBoolean authenticated = new AtomicBoolean(true);
        List<String> cleanupOperations = new ArrayList<>();
        HttpSession session = createInterfaceProxy(
                HttpSession.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("invalidate")) {
                        assertFalse(authenticated.get());
                        cleanupOperations.add("session invalidated");
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletRequest request = createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("getSession")) {
                        assertFalse(authenticated.get());
                        return session;
                    }
                    if (method.getName().equals("logout")) {
                        cleanupOperations.add("logout");
                        authenticated.set(false);
                    }
                    return defaultValue(method.getReturnType());
                });

        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);

        assertAll(
                () -> assertEquals(List.of("logout", "session invalidated"), cleanupOperations),
                () -> assertFalse(authenticated.get()));
    }

    @Test
    void doesNotInvalidateAgainWhenContainerLogoutAlreadyRemovedTheSession() throws Exception {
        AtomicBoolean authenticated = new AtomicBoolean(true);
        AtomicInteger sessionInvalidations = new AtomicInteger();
        HttpSession session = createInterfaceProxy(
                HttpSession.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("invalidate")) {
                        sessionInvalidations.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletRequest request = createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("logout")) {
                        authenticated.set(false);
                    }
                    if (method.getName().equals("getSession")) {
                        return authenticated.get() ? session : null;
                    }
                    return defaultValue(method.getReturnType());
                });

        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);

        assertAll(
                () -> assertFalse(authenticated.get()),
                () -> assertEquals(0, sessionInvalidations.get()));
    }

    @Test
    void logsOutWithoutCreatingAReplacementWhenThereIsNoSession() throws Exception {
        AtomicInteger sessionLookups = new AtomicInteger();
        AtomicInteger logoutCalls = new AtomicInteger();
        HttpServletRequest request = createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("getSession")) {
                        assertEquals(Boolean.FALSE, arguments[0]);
                        sessionLookups.incrementAndGet();
                        return null;
                    }
                    if (method.getName().equals("logout")) {
                        logoutCalls.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                });

        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);

        assertAll(
                () -> assertEquals(1, sessionLookups.get()),
                () -> assertEquals(1, logoutCalls.get()));
    }

    @Test
    void logoutFailureStillInvalidatesTheRemainingSessionAndPreservesTheFailure() {
        jakarta.servlet.ServletException logoutFailure =
                new jakarta.servlet.ServletException("Simulated logout failure.");
        AtomicInteger invalidationCount = new AtomicInteger();
        HttpSession session = createInterfaceProxy(
                HttpSession.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("invalidate")) {
                        invalidationCount.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletRequest request = createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, method, arguments) -> switch (method.getName()) {
                    case "logout" -> throw logoutFailure;
                    case "getSession" -> session;
                    default -> defaultValue(method.getReturnType());
                });

        jakarta.servlet.ServletException thrownFailure = assertThrows(
                jakarta.servlet.ServletException.class,
                () -> AuthenticatedSessionSecurity.invalidateSessionAndLogout(request));

        assertAll(
                () -> assertSame(logoutFailure, thrownFailure),
                () -> assertEquals(1, invalidationCount.get()),
                () -> assertEquals(0, thrownFailure.getSuppressed().length));
    }

    @Test
    void invalidationFailureIsSuppressedUnderTheOriginalLogoutFailure() {
        jakarta.servlet.ServletException logoutFailure =
                new jakarta.servlet.ServletException("Simulated logout failure.");
        IllegalStateException invalidationFailure =
                new IllegalStateException("Simulated invalidation failure.");
        HttpSession session = createInterfaceProxy(
                HttpSession.class,
                (ignoredProxy, method, arguments) -> {
                    if (method.getName().equals("invalidate")) {
                        throw invalidationFailure;
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletRequest request = createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, method, arguments) -> switch (method.getName()) {
                    case "logout" -> throw logoutFailure;
                    case "getSession" -> session;
                    default -> defaultValue(method.getReturnType());
                });

        jakarta.servlet.ServletException thrownFailure = assertThrows(
                jakarta.servlet.ServletException.class,
                () -> AuthenticatedSessionSecurity.invalidateSessionAndLogout(request));

        assertAll(
                () -> assertSame(logoutFailure, thrownFailure),
                () -> assertEquals(1, thrownFailure.getSuppressed().length),
                () -> assertSame(invalidationFailure, thrownFailure.getSuppressed()[0]));
    }

    @Test
    void rotatesAnExistingSession() {
        RequestHarness requestHarness = new RequestHarness(true);

        HttpSession rotatedSession = AuthenticatedSessionSecurity.rotateSessionIdentifier(
                requestHarness.request());

        assertAll(
                () -> assertSame(requestHarness.session(), rotatedSession),
                () -> assertEquals(1, requestHarness.sessionIdentifierChanges.get()),
                () -> assertEquals(0, requestHarness.newSessionRequests.get()));
    }

    @Test
    void createsAFreshSessionWhenAuthenticationStartedWithoutOne() {
        RequestHarness requestHarness = new RequestHarness(false);

        HttpSession createdSession = AuthenticatedSessionSecurity.rotateSessionIdentifier(
                requestHarness.request());

        assertAll(
                () -> assertNotNull(createdSession),
                () -> assertEquals(0, requestHarness.sessionIdentifierChanges.get()),
                () -> assertEquals(1, requestHarness.newSessionRequests.get()));
    }

    @Test
    void establishesAndValidatesTheDatabasePasswordVersionInTheRotatedSession() {
        RequestHarness requestHarness = new RequestHarness(true);
        ApplicationUser user = new ApplicationUser();
        user.setPasswordVersion(7);

        AuthenticatedSessionSecurity.establishAuthenticatedSession(
                requestHarness.request(),
                7);

        assertAll(
                () -> assertEquals(1, requestHarness.sessionIdentifierChanges.get()),
                () -> assertEquals(0, requestHarness.newSessionRequests.get()),
                () -> assertEquals(
                        7L,
                        requestHarness.sessionHarness.attribute(
                                AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE)),
                () -> assertEquals(
                        AuthenticatedSessionSecurity.AUTHENTICATED_SESSION_LIFETIME_SECONDS,
                        requestHarness.sessionHarness.maximumInactiveInterval.get()),
                () -> org.junit.jupiter.api.Assertions.assertTrue(
                        AuthenticatedSessionSecurity.hasCurrentPasswordVersion(
                        requestHarness.request(),
                        user)));

        user.setPasswordVersion(8);
        assertFalse(AuthenticatedSessionSecurity.hasCurrentPasswordVersion(
                requestHarness.request(),
                user));
    }

    @Test
    void rejectsANegativePasswordVersionBeforeCreatingASession() {
        RequestHarness requestHarness = new RequestHarness(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> AuthenticatedSessionSecurity.establishAuthenticatedSession(
                        requestHarness.request(),
                        -1));
        assertEquals(0, requestHarness.newSessionRequests.get());
    }

    private static final class RequestHarness {
        private final AtomicInteger sessionIdentifierChanges = new AtomicInteger();
        private final AtomicInteger newSessionRequests = new AtomicInteger();
        private final AtomicInteger nextSessionIdentifier = new AtomicInteger();
        private final AtomicReference<SessionHarness> currentSession = new AtomicReference<>();
        private final HttpServletRequest request;
        private SessionHarness sessionHarness;

        private RequestHarness(boolean existingSession) {
            if (existingSession) {
                createSession();
            }
            request = createInterfaceProxy(
                    HttpServletRequest.class,
                    (ignoredProxy, method, arguments) -> switch (method.getName()) {
                        case "getSession" -> getSession(arguments);
                        case "changeSessionId" -> changeSessionIdentifier();
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private HttpServletRequest request() {
            return request;
        }

        private HttpSession session() {
            SessionHarness currentSessionHarness = currentSession.get();
            return currentSessionHarness == null ? null : currentSessionHarness.session();
        }

        private Object getSession(Object[] arguments) {
            boolean create = arguments == null
                    || arguments.length == 0
                    || (Boolean) arguments[0];
            if (currentSession.get() == null && create) {
                newSessionRequests.incrementAndGet();
                createSession();
            }
            return session();
        }

        private Object changeSessionIdentifier() {
            sessionIdentifierChanges.incrementAndGet();
            String newIdentifier = "rotated-session-" + nextSessionIdentifier.incrementAndGet();
            currentSession.get().changeIdentifier(newIdentifier);
            return newIdentifier;
        }

        private void createSession() {
            sessionHarness = new SessionHarness(
                    "session-" + nextSessionIdentifier.incrementAndGet());
            currentSession.set(sessionHarness);
        }
    }

    private static final class SessionHarness {
        private final Map<String, Object> attributes = new HashMap<>();
        private final AtomicReference<String> identifier;
        private final AtomicInteger maximumInactiveInterval = new AtomicInteger();
        private final HttpSession session;

        private SessionHarness(String identifier) {
            this.identifier = new AtomicReference<>(identifier);
            session = createInterfaceProxy(
                    HttpSession.class,
                    (ignoredProxy, method, arguments) -> switch (method.getName()) {
                        case "getId" -> this.identifier.get();
                        case "getAttribute" -> attribute((String) arguments[0]);
                        case "setAttribute" -> setAttribute(
                                (String) arguments[0],
                                arguments[1]);
                        case "setMaxInactiveInterval" -> {
                            maximumInactiveInterval.set((Integer) arguments[0]);
                            yield null;
                        }
                        case "invalidate" -> {
                            attributes.clear();
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private HttpSession session() {
            return session;
        }

        private void changeIdentifier(String newIdentifier) {
            identifier.set(newIdentifier);
        }

        private Object attribute(String name) {
            return attributes.get(name);
        }

        private Object setAttribute(String name, Object value) {
            attributes.put(name, value);
            return null;
        }
    }
}
