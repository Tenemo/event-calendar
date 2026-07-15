package app.security;

import app.user.ApplicationUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class AuthenticatedSessionSecurity {
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
            ApplicationUser user) {
        if (user == null) {
            throw new IllegalArgumentException("Authenticated user is required.");
        }
        rotateSessionIdentifier(request);
        request.getSession(true).setAttribute(PASSWORD_VERSION_SESSION_ATTRIBUTE, user.getPasswordVersion());
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
