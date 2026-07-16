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
    public static final String CALENDAR_TOKEN_REQUEST_ATTRIBUTE = "calendarToken";
    public static final String NOT_FOUND_REQUEST_ATTRIBUTE = "calendarNotFound";
    public static final String CALENDAR_REQUEST_ATTRIBUTE = "calendar";

    private static final String CALENDAR_TEMPLATE_PATH = "/public-calendar.xhtml";
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
                || !(servletResponse instanceof HttpServletResponse response)
                || !"GET".equals(request.getMethod())) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String calendarToken = CalendarPublicToken.fromRequestPath(
                request.getContextPath(), request.getRequestURI());
        if (calendarToken == null) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String sourceIdentifier = clientRequestSourceResolver.resolve(request);
        try (CalendarLinkRequestThrottle.Admission admission = requestThrottle.tryAcquire(sourceIdentifier)) {
            if (!admission.isAccepted()) {
                sendRateLimitResponse(response, admission.getRetryAfterSeconds());
                return;
            }
            forwardCalendar(request, response, calendarToken);
        }
    }

    private void forwardCalendar(
            HttpServletRequest request,
            HttpServletResponse response,
            String calendarToken) throws IOException, ServletException {
        request.setAttribute(CALENDAR_TOKEN_REQUEST_ATTRIBUTE, calendarToken);
        RequestDispatcher requestDispatcher = request.getRequestDispatcher(CALENDAR_TEMPLATE_PATH);
        Calendar calendar;
        try {
            calendar = calendarAccessService.requireCalendarReadableByToken(
                    currentUser.find().orElse(null), calendarToken);
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
