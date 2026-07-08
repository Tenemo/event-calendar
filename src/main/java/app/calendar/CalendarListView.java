package app.calendar;

import app.security.CurrentUser;
import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

@Named
@RequestScoped
public class CalendarListView {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    private String calendarName;
    private List<CalendarMembershipSummary> calendarMemberships = List.of();

    @PostConstruct
    public void loadCalendars() {
        calendarMemberships = calendarService.findCalendarsForUser(currentUser.require());
    }

    public List<CalendarMembershipSummary> getCalendarMemberships() {
        return calendarMemberships;
    }

    public void createCalendar() {
        try {
            calendarService.createCalendar(currentUser.require(), calendarName);
            calendarName = null;
            loadCalendars();
            FacesContext.getCurrentInstance().addMessage(
                    null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Calendar created.", "The calendar is ready."));
        } catch (ValidationException exception) {
            FacesContext.getCurrentInstance().addMessage(
                    null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Calendar could not be created.", exception.getMessage()));
        }
    }

    public String getCalendarName() {
        return calendarName;
    }

    public void setCalendarName(String calendarName) {
        this.calendarName = calendarName;
    }
}
