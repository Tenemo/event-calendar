package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.user.ApplicationUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AuthenticatedSessionSecurityTest {
    @Test
    void invalidatesTheOwnedSessionBeforeRemovingTheAuthenticatedIdentity() throws Exception {
        AtomicBoolean authenticated = new AtomicBoolean(true);
        List<String> cleanupOperations = new ArrayList<>();
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                HttpSession.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("invalidate")) {
                        assertTrue(authenticated.get());
                        cleanupOperations.add("session invalidated");
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getSession")) {
                        if (!authenticated.get()) {
                            throw new AssertionError("An anonymous identity cannot access the authenticated session.");
                        }
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
                () -> assertEquals(List.of("session invalidated", "logout"), cleanupOperations),
                () -> assertFalse(authenticated.get()));
    }

    @Test
    void logsOutWithoutCreatingAReplacementWhenThereIsNoSession() throws Exception {
        AtomicInteger sessionLookups = new AtomicInteger();
        AtomicInteger logoutCalls = new AtomicInteger();
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> {
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
    void rotatesAnExistingUnauthenticatedSessionAfterAuthentication() {
        AtomicInteger sessionIdentifierChanges = new AtomicInteger();
        AtomicInteger newSessionRequests = new AtomicInteger();
        HttpServletRequest request = request(true, sessionIdentifierChanges, newSessionRequests);

        AuthenticatedSessionSecurity.rotateSessionIdentifier(request);

        assertAll(
                () -> assertEquals(1, sessionIdentifierChanges.get()),
                () -> assertEquals(0, newSessionRequests.get()));
    }

    @Test
    void createsAFreshSessionWhenAuthenticationStartedWithoutOne() {
        AtomicInteger sessionIdentifierChanges = new AtomicInteger();
        AtomicInteger newSessionRequests = new AtomicInteger();
        HttpServletRequest request = request(false, sessionIdentifierChanges, newSessionRequests);

        AuthenticatedSessionSecurity.rotateSessionIdentifier(request);

        assertAll(
                () -> assertEquals(0, sessionIdentifierChanges.get()),
                () -> assertEquals(1, newSessionRequests.get()));
    }

    @Test
    void establishesAndValidatesTheDatabasePasswordVersionInTheRotatedSession() {
        AtomicInteger sessionIdentifierChanges = new AtomicInteger();
        AtomicInteger newSessionRequests = new AtomicInteger();
        AtomicReference<Object> sessionPasswordVersion = new AtomicReference<>();
        HttpServletRequest request = request(
                true,
                sessionIdentifierChanges,
                newSessionRequests,
                sessionPasswordVersion);
        ApplicationUser user = new ApplicationUser();
        user.setPasswordVersion(7);

        AuthenticatedSessionSecurity.establishAuthenticatedSession(request, user);

        assertAll(
                () -> assertEquals(1, sessionIdentifierChanges.get()),
                () -> assertEquals(7L, sessionPasswordVersion.get()),
                () -> assertTrue(AuthenticatedSessionSecurity.hasCurrentPasswordVersion(request, user)));

        user.setPasswordVersion(8);
        assertFalse(AuthenticatedSessionSecurity.hasCurrentPasswordVersion(request, user));
    }

    private HttpServletRequest request(
            boolean existingSession,
            AtomicInteger sessionIdentifierChanges,
            AtomicInteger newSessionRequests) {
        return request(
                existingSession,
                sessionIdentifierChanges,
                newSessionRequests,
                new AtomicReference<>());
    }

    private HttpServletRequest request(
            boolean existingSession,
            AtomicInteger sessionIdentifierChanges,
            AtomicInteger newSessionRequests,
            AtomicReference<Object> sessionPasswordVersion) {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                HttpSession.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setAttribute")
                            && AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE.equals(arguments[0])) {
                        sessionPasswordVersion.set(arguments[1]);
                        return null;
                    }
                    if (method.getName().equals("getAttribute")
                            && AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE.equals(arguments[0])) {
                        return sessionPasswordVersion.get();
                    }
                    return defaultValue(method.getReturnType());
                });
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> {
                    if ("getSession".equals(method.getName())
                            && arguments != null
                            && arguments.length == 1) {
                        boolean createSession = (boolean) arguments[0];
                        if (createSession) {
                            newSessionRequests.incrementAndGet();
                            return session;
                        }
                        return existingSession ? session : null;
                    }
                    if ("changeSessionId".equals(method.getName())) {
                        sessionIdentifierChanges.incrementAndGet();
                        return "rotated-session-identifier";
                    }
                    return defaultValue(method.getReturnType());
                });
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
}
