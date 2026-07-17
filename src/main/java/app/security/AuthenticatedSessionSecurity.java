package app.security;

import app.user.ApplicationUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Objects;

public final class AuthenticatedSessionSecurity {
    static final int AUTHENTICATED_SESSION_LIFETIME_SECONDS =
            Math.toIntExact(Duration.ofDays(30).toSeconds());
    static final String PASSWORD_VERSION_SESSION_ATTRIBUTE =
            AuthenticatedSessionSecurity.class.getName() + ".passwordVersion";

    private AuthenticatedSessionSecurity() {
    }

    public static HttpSession rotateSessionIdentifier(HttpServletRequest request) {
        HttpSession existingSession = request.getSession(false);
        if (existingSession == null) {
            return Objects.requireNonNull(
                    request.getSession(true),
                    "The servlet container did not create a requested session.");
        }
        Objects.requireNonNull(
                request.changeSessionId(),
                "The servlet container did not return the rotated session identifier.");
        return existingSession;
    }

    public static void establishAuthenticatedSession(
            HttpServletRequest request,
            long validatedPasswordVersion) {
        if (validatedPasswordVersion < 0) {
            throw new IllegalArgumentException("Validated password version cannot be negative.");
        }
        HttpSession authenticatedSession = rotateSessionIdentifier(request);
        authenticatedSession.setMaxInactiveInterval(AUTHENTICATED_SESSION_LIFETIME_SECONDS);
        authenticatedSession.setAttribute(
                PASSWORD_VERSION_SESSION_ATTRIBUTE,
                validatedPasswordVersion);
    }

    public static void invalidateSessionAndLogout(HttpServletRequest request) throws ServletException {
        request.logout();
        HttpSession remainingSession = request.getSession(false);
        if (remainingSession != null) {
            remainingSession.invalidate();
        }
    }

    public static boolean hasCurrentPasswordVersion(
            HttpServletRequest request,
            ApplicationUser user) {
        if (request == null || user == null) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object sessionPasswordVersion = session.getAttribute(PASSWORD_VERSION_SESSION_ATTRIBUTE);
        return sessionPasswordVersion instanceof Number passwordVersion
                && passwordVersion.longValue() == user.getPasswordVersion();
    }
}
