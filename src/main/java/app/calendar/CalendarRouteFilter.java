package app.calendar;

import app.membership.CalendarAccessService;
import app.security.ClientRequestSourceResolver;
import app.security.CurrentUser;
import app.util.NotFoundException;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

public class CalendarRouteFilter implements Filter {
    public static final String CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE = "calendarLinkToken";
    public static final String NOT_FOUND_REQUEST_ATTRIBUTE = "calendarNotFound";
    public static final String CALENDAR_REQUEST_ATTRIBUTE = "calendar";

    private static final String CALENDAR_TEMPLATE_PATH = "/calendar.xhtml";
    private static final String RATE_LIMIT_MESSAGE = "Too many calendar link requests. Try again later.";

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarLinkRequestThrottle requestThrottle;

    @Inject
    private ClientRequestSourceResolver clientRequestSourceResolver;

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        if (isLegacyCalendarRoute(request)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String calendarLinkToken = CalendarLinkToken.fromRequestPath(
                request.getContextPath(), request.getRequestURI());
        if (calendarLinkToken == null) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String sourceIdentifier = clientRequestSourceResolver.resolve(request);
        try (CalendarLinkRequestThrottle.Admission admission = requestThrottle.tryAcquire(sourceIdentifier)) {
            if (!admission.isAccepted()) {
                sendRateLimitResponse(response, admission.getRetryAfterSeconds());
                return;
            }
            if (!isReadOnlyRequest(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            forwardCalendar(request, response, calendarLinkToken);
        }
    }

    private static boolean isReadOnlyRequest(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod());
    }

    private static boolean isLegacyCalendarRoute(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        if (contextPath == null || requestUri == null || !requestUri.startsWith(contextPath)) {
            return false;
        }
        String applicationPath = requestUri.substring(contextPath.length());
        return applicationPath.equals("/calendar") || applicationPath.startsWith("/calendar/");
    }

    private void forwardCalendar(
            HttpServletRequest request,
            HttpServletResponse response,
            String calendarLinkToken) throws IOException, ServletException {
        request.setAttribute(CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE, calendarLinkToken);
        RequestDispatcher requestDispatcher = request.getRequestDispatcher(CALENDAR_TEMPLATE_PATH);
        Calendar calendar;
        try {
            calendar = calendarAccessService.requireCalendarReadableByLinkToken(
                    currentUser.find().orElse(null), calendarLinkToken);
        } catch (NotFoundException exception) {
            request.setAttribute(NOT_FOUND_REQUEST_ATTRIBUTE, true);
            requestDispatcher.forward(request, new FixedStatusResponse(response, HttpServletResponse.SC_NOT_FOUND));
            return;
        }

        request.setAttribute(CALENDAR_REQUEST_ATTRIBUTE, calendar);
        requestDispatcher.forward(request, response);
    }

    private void sendRateLimitResponse(HttpServletResponse response, int retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Integer.toString(retryAfterSeconds));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(RATE_LIMIT_MESSAGE);
    }

    private static final class FixedStatusResponse extends HttpServletResponseWrapper {
        private final int status;

        private FixedStatusResponse(HttpServletResponse response, int status) {
            super(response);
            this.status = status;
            super.setStatus(status);
        }

        @Override
        public void setStatus(int status) {
            super.setStatus(this.status);
        }

        @Override
        public void reset() {
            super.reset();
            super.setStatus(status);
        }
    }
}
