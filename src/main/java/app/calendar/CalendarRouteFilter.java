package app.calendar;

import app.membership.CalendarAccessService;
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
    public static final String CALENDAR_NOT_FOUND_REQUEST_ATTRIBUTE = "calendarNotFound";
    public static final String CALENDAR_REQUEST_ATTRIBUTE = "calendar";
    private static final String CALENDAR_VIEW_PATH = "/WEB-INF/views/calendar.xhtml";
    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CurrentUser currentUser;

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

        String calendarLinkToken = CalendarLinkToken.fromRequestPath(
                request.getContextPath(), request.getRequestURI());
        if (calendarLinkToken == null) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        if (!isSupportedCalendarRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        forwardCalendar(request, response, calendarLinkToken);
    }

    private static boolean isSupportedCalendarRequest(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())
                || "POST".equalsIgnoreCase(request.getMethod());
    }

    private void forwardCalendar(
            HttpServletRequest request,
            HttpServletResponse response,
            String calendarLinkToken) throws IOException, ServletException {
        request.setAttribute(CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE, calendarLinkToken);
        RequestDispatcher requestDispatcher = request.getRequestDispatcher(CALENDAR_VIEW_PATH);
        Calendar calendar;
        try {
            calendar = calendarAccessService.requireCalendarReadableByLinkToken(
                    currentUser.find().orElse(null), calendarLinkToken);
        } catch (NotFoundException exception) {
            request.setAttribute(CALENDAR_NOT_FOUND_REQUEST_ATTRIBUTE, true);
            requestDispatcher.forward(request, new FixedStatusResponse(response, HttpServletResponse.SC_NOT_FOUND));
            return;
        }

        request.setAttribute(CALENDAR_REQUEST_ATTRIBUTE, calendar);
        requestDispatcher.forward(request, response);
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
