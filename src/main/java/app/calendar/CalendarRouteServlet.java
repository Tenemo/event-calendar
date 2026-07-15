package app.calendar;

import app.membership.CalendarAccessService;
import app.security.CurrentUser;
import app.util.NotFoundException;
import jakarta.inject.Inject;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

@WebServlet("/calendar/*")
public class CalendarRouteServlet extends HttpServlet {
    public static final String CALENDAR_TOKEN_REQUEST_ATTRIBUTE = "calendarToken";
    public static final String NOT_FOUND_REQUEST_ATTRIBUTE = "calendarNotFound";
    public static final String CALENDAR_REQUEST_ATTRIBUTE = "calendar";

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CurrentUser currentUser;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String calendarToken = tokenFromPath(request.getPathInfo());
        request.setAttribute(CALENDAR_TOKEN_REQUEST_ATTRIBUTE, calendarToken);
        RequestDispatcher requestDispatcher = request.getRequestDispatcher("/public-calendar.xhtml");
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

    static String tokenFromPath(String pathInfo) {
        if (pathInfo == null || pathInfo.length() <= 1) {
            return null;
        }
        String token = pathInfo.substring(1);
        if (token.isBlank() || token.contains("/")) {
            return null;
        }
        return token;
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
