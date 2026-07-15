package app.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class AuthenticatedSessionSecurity {
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
}
