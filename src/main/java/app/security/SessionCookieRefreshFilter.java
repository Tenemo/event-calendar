package app.security;

import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@WebFilter(urlPatterns = {"/app/*", "/calendar/*", "/public-calendar.xhtml"})
public class SessionCookieRefreshFilter implements Filter {
    static final int SESSION_COOKIE_LIFETIME_SECONDS = Math.toIntExact(Duration.ofDays(30).toSeconds());

    private static final String SESSION_COOKIE_NAME = "JSESSIONID";
    private static final String COOKIE_SECURE_ENVIRONMENT_VARIABLE = "COOKIE_SECURE";

    @Inject
    private CurrentUser currentUser;

    private final boolean secureCookie;

    public SessionCookieRefreshFilter() {
        this.secureCookie = Boolean.parseBoolean(
                System.getenv().getOrDefault(COOKIE_SECURE_ENVIRONMENT_VARIABLE, "false"));
    }

    SessionCookieRefreshFilter(CurrentUser currentUser, boolean secureCookie) {
        this.currentUser = currentUser;
        this.secureCookie = secureCookie;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain)
            throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest request
                && servletResponse instanceof HttpServletResponse response) {
            SessionCookieRefreshResponse responseWrapper =
                    new SessionCookieRefreshResponse(request, response);
            filterChain.doFilter(servletRequest, responseWrapper);
            if (!responseWrapper.isCommitted()) {
                responseWrapper.refreshSessionCookie();
            }
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    private void refreshAuthenticatedSessionCookie(
            HttpServletRequest request,
            HttpServletResponse response) {
        Principal authenticatedPrincipal = request.getUserPrincipal();
        if (!request.isRequestedSessionIdValid()
                || authenticatedPrincipal == null
                || !currentUser.isSignedIn()) {
            return;
        }

        findRequestedSessionCookie(request).ifPresent(sessionCookie -> {
            Cookie refreshedSessionCookie = new Cookie(SESSION_COOKIE_NAME, sessionCookie.getValue());
            refreshedSessionCookie.setPath("/");
            refreshedSessionCookie.setHttpOnly(true);
            refreshedSessionCookie.setSecure(secureCookie || request.isSecure());
            refreshedSessionCookie.setMaxAge(SESSION_COOKIE_LIFETIME_SECONDS);
            refreshedSessionCookie.setAttribute("SameSite", "Lax");
            response.addCookie(refreshedSessionCookie);
        });
    }

    private Optional<Cookie> findRequestedSessionCookie(HttpServletRequest request) {
        Cookie[] requestCookies = request.getCookies();
        if (requestCookies == null) {
            return Optional.empty();
        }
        List<Cookie> sessionCookies = Arrays.stream(requestCookies)
                .filter(cookie -> SESSION_COOKIE_NAME.equals(cookie.getName()))
                .toList();
        return sessionCookies.size() == 1
                ? Optional.of(sessionCookies.getFirst())
                : Optional.empty();
    }

    private final class SessionCookieRefreshResponse extends HttpServletResponseWrapper {
        private final HttpServletRequest request;
        private boolean sessionCookieRefreshed;
        private boolean refreshSuppressed;

        private SessionCookieRefreshResponse(
                HttpServletRequest request,
                HttpServletResponse response) {
            super(response);
            this.request = request;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            refreshSessionCookie();
            return super.getWriter();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            refreshSessionCookie();
            return super.getOutputStream();
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            refreshSessionCookie();
            super.sendRedirect(location);
        }

        @Override
        public void flushBuffer() throws IOException {
            refreshSessionCookie();
            super.flushBuffer();
        }

        @Override
        public void sendError(int statusCode) throws IOException {
            refreshSuppressed = true;
            super.sendError(statusCode);
        }

        @Override
        public void sendError(int statusCode, String message) throws IOException {
            refreshSuppressed = true;
            super.sendError(statusCode, message);
        }

        private void refreshSessionCookie() {
            if (sessionCookieRefreshed || refreshSuppressed || isCommitted()) {
                return;
            }
            refreshAuthenticatedSessionCookie(request, this);
            sessionCookieRefreshed = true;
        }
    }
}
