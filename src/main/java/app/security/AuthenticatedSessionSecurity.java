package app.security;

import app.user.ApplicationUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;

public final class AuthenticatedSessionSecurity {
    static final int AUTHENTICATED_SESSION_LIFETIME_SECONDS =
            Math.toIntExact(Duration.ofDays(30).toSeconds());
    static final String PASSWORD_VERSION_SESSION_ATTRIBUTE =
            AuthenticatedSessionSecurity.class.getName() + ".passwordVersion";

    private AuthenticatedSessionSecurity() {
    }

    public static void rotateSessionIdentifier(HttpServletRequest request) {
        HttpSession existingSession = request.getSession(false);
        if (existingSession == null) {
            request.getSession(true);
            return;
        }
        request.changeSessionId();
    }

    public static void establishAuthenticatedSession(
            HttpServletRequest request,
            long validatedPasswordVersion) {
        if (validatedPasswordVersion < 0) {
            throw new IllegalArgumentException("Validated password version cannot be negative.");
        }
        rotateSessionIdentifier(request);
        HttpSession authenticatedSession = request.getSession(true);
        authenticatedSession.setMaxInactiveInterval(AUTHENTICATED_SESSION_LIFETIME_SECONDS);
        authenticatedSession.setAttribute(
                PASSWORD_VERSION_SESSION_ATTRIBUTE,
                validatedPasswordVersion);
    }

    public static void invalidateSessionAndLogout(HttpServletRequest request) throws ServletException {
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            existingSession.invalidate();
        }
        request.logout();
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
