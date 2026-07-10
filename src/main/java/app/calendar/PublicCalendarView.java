package app.calendar;

import app.event.CalendarEventRow;
import app.event.CalendarEventService;
import app.membership.CalendarAccessService;
import app.util.NotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

@Named
@RequestScoped
public class PublicCalendarView {
    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarEventService calendarEventService;

    @Inject
    private CalendarTimeService calendarTimeService;

    private boolean available;
    private String calendarName;
    private String calendarDescription;
    private String timeZone;
    private List<CalendarEventRow> events = List.of();

    @PostConstruct
    public void load() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        String publicToken = (String) request.getAttribute(PublicCalendarRouteServlet.PUBLIC_TOKEN_REQUEST_ATTRIBUTE);

        try {
            if (Boolean.TRUE.equals(request.getAttribute(PublicCalendarRouteServlet.NOT_FOUND_REQUEST_ATTRIBUTE))) {
                throw new NotFoundException("Calendar was not found.");
            }
            Calendar calendar = (Calendar) request.getAttribute(PublicCalendarRouteServlet.CALENDAR_REQUEST_ATTRIBUTE);
            if (calendar == null) {
                calendar = calendarAccessService.requirePublicReadableCalendar(publicToken);
            }
            calendarName = calendar.getName();
            calendarDescription = calendar.getDescription();
            timeZone = calendar.getTimezone();
            events = calendarEventService.findPublicEvents(publicToken, null, null).stream()
                    .map(event -> CalendarEventRow.from(event, timeZone, calendarTimeService))
                    .toList();
            available = true;
        } catch (NotFoundException exception) {
            available = false;
            boolean routeAlreadyMarkedNotFound = Boolean.TRUE.equals(
                    request.getAttribute(PublicCalendarRouteServlet.NOT_FOUND_REQUEST_ATTRIBUTE));
            request.setAttribute(PublicCalendarRouteServlet.NOT_FOUND_REQUEST_ATTRIBUTE, true);
            if (!routeAlreadyMarkedNotFound) {
                facesContext.getExternalContext().setResponseStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }

    public boolean isAvailable() { return available; }
    public String getCalendarName() { return calendarName; }
    public String getCalendarDescription() { return calendarDescription; }
    public String getTimeZone() { return timeZone; }
    public List<CalendarEventRow> getEvents() { return events; }
}
