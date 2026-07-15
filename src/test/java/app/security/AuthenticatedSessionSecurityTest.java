package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AuthenticatedSessionSecurityTest {
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

    private HttpServletRequest request(
            boolean existingSession,
            AtomicInteger sessionIdentifierChanges,
            AtomicInteger newSessionRequests) {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                HttpSession.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
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
