package app.security;

import app.calendar.CalendarLinkToken;
import app.calendar.CalendarRouteFilter;
import app.web.RelativeRedirect;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class SessionCookieRefreshFilter implements Filter {
    static final int SESSION_COOKIE_LIFETIME_SECONDS =
            AuthenticatedSessionSecurity.AUTHENTICATED_SESSION_LIFETIME_SECONDS;

    private static final String SESSION_COOKIE_NAME = "JSESSIONID";
    private static final String SINGLE_SIGN_ON_COOKIE_NAME = "LtpaToken2";

    @Inject
    private CurrentUser currentUser;

    public SessionCookieRefreshFilter() {
    }

    SessionCookieRefreshFilter(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain)
            throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest request
                && servletResponse instanceof HttpServletResponse response) {
            expireRejectedSingleSignOnCookie(request, response);
            if (!isManagedRequest(request)) {
                filterChain.doFilter(servletRequest, servletResponse);
                return;
            }
            if (clearStaleAuthenticatedSession(request, response)) {
                if (isReadOnlyCanonicalCalendarRequest(request)) {
                    filterChain.doFilter(servletRequest, servletResponse);
                } else {
                    RelativeRedirect.send(
                            request,
                            response,
                            request.getContextPath(),
                            ExpiredLoginViewExceptionHandler.RECOVERY_ROUTE);
                }
                return;
            }
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

    private boolean clearStaleAuthenticatedSession(
            HttpServletRequest request,
            HttpServletResponse response) throws ServletException {
        if (request.getUserPrincipal() == null || currentUser.isSignedIn()) {
            return false;
        }

        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        expireAuthenticationCookies(response);
        return true;
    }

    private boolean isReadOnlyCanonicalCalendarRequest(HttpServletRequest request) {
        return ("GET".equalsIgnoreCase(request.getMethod())
                        || "HEAD".equalsIgnoreCase(request.getMethod()))
                && isCanonicalCalendarRequest(request);
    }

    private void expireAuthenticationCookies(HttpServletResponse response) {
        response.addCookie(expiredCookie(SESSION_COOKIE_NAME));
        response.addCookie(expiredCookie(SINGLE_SIGN_ON_COOKIE_NAME));
    }

    private void expireRejectedSingleSignOnCookie(
            HttpServletRequest request,
            HttpServletResponse response) {
        if (request.getUserPrincipal() == null && hasCookie(request, SINGLE_SIGN_ON_COOKIE_NAME)) {
            response.addCookie(expiredCookie(SINGLE_SIGN_ON_COOKIE_NAME));
        }
    }

    private boolean hasCookie(HttpServletRequest request, String cookieName) {
        Cookie[] requestCookies = request.getCookies();
        return requestCookies != null
                && Arrays.stream(requestCookies).anyMatch(cookie -> cookieName.equals(cookie.getName()));
    }

    private Cookie expiredCookie(String cookieName) {
        Cookie expiredCookie = new Cookie(cookieName, "");
        expiredCookie.setPath("/");
        expiredCookie.setHttpOnly(true);
        expiredCookie.setSecure(true);
        expiredCookie.setMaxAge(0);
        expiredCookie.setAttribute("SameSite", "Lax");
        return expiredCookie;
    }

    private boolean isManagedRequest(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        if (contextPath == null || requestUri == null || !requestUri.startsWith(contextPath)) {
            return false;
        }
        String applicationPath = requestUri.substring(contextPath.length());
        return applicationPath.equals("/app")
                || applicationPath.startsWith("/app/")
                || applicationPath.equals("/calendar.xhtml")
                || isCanonicalCalendarRequest(request);
    }

    private boolean isCanonicalCalendarRequest(HttpServletRequest request) {
        Object forwardedCalendarLinkToken =
                request.getAttribute(CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE);
        return (forwardedCalendarLinkToken instanceof String token && CalendarLinkToken.isValid(token))
                || CalendarLinkToken.fromRequestPath(request.getContextPath(), request.getRequestURI()) != null;
    }

    private void refreshAuthenticatedSessionCookie(
            HttpServletRequest request,
            HttpServletResponse response) {
        Principal authenticatedPrincipal = request.getUserPrincipal();
        if (authenticatedPrincipal == null
                || !currentUser.isSignedIn()
                || !request.isRequestedSessionIdValid()) {
            return;
        }

        findRequestedSessionCookie(request).ifPresent(sessionCookie -> {
            Cookie refreshedSessionCookie = new Cookie(SESSION_COOKIE_NAME, sessionCookie.getValue());
            refreshedSessionCookie.setPath("/");
            refreshedSessionCookie.setHttpOnly(true);
            refreshedSessionCookie.setSecure(true);
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
