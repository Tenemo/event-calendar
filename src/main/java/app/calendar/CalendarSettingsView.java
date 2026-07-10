package app.calendar;

import app.config.ApplicationUrlService;
import app.security.CurrentUser;
import app.user.AppUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;

@Named
@ViewScoped
public class CalendarSettingsView implements Serializable {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    @Inject
    private ApplicationUrlService applicationUrlService;

    private Long calendarId;
    private String name;
    private String description;
    private String timeZone;
    private boolean publicAccessEnabled;
    private String publicToken;
    private Integer version;
    private boolean available;

    public void load() {
        try {
            AppUser actor = currentUser.require();
            Calendar calendar = calendarService.requireAdminCalendar(actor, calendarId);
            copyCalendar(calendar);
            available = true;
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void save() {
        try {
            Calendar calendar = calendarService.updateCalendarSettings(
                    currentUser.require(),
                    calendarId,
                    name,
                    description,
                    timeZone,
                    publicAccessEnabled,
                    version);
            copyCalendar(calendar);
            addMessage(FacesMessage.SEVERITY_INFO, "Calendar settings saved.", "The calendar has been updated.");
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Settings could not be saved.", exception.getMessage());
        }
    }

    public void rotatePublicLink() {
        try {
            publicToken = calendarService.rotatePublicToken(currentUser.require(), calendarId, version);
            Calendar calendar = calendarService.requireActiveCalendar(calendarId);
            version = calendar.getVersion();
            addMessage(FacesMessage.SEVERITY_INFO, "Public link rotated.", "The previous public link no longer works.");
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Public link could not be rotated.", exception.getMessage());
        }
    }

    public String getPublicLink() {
        return applicationUrlService.linkTo("/calendar/" + publicToken);
    }

    private void copyCalendar(Calendar calendar) {
        name = calendar.getName();
        description = calendar.getDescription();
        timeZone = calendar.getTimezone();
        publicAccessEnabled = calendar.isPublicAccessEnabled();
        publicToken = calendar.getPublicToken();
        version = calendar.getVersion();
    }

    private void markNotFound() {
        available = false;
        FacesContext.getCurrentInstance().getExternalContext().setResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public Long getCalendarId() { return calendarId; }
    public void setCalendarId(Long calendarId) { this.calendarId = calendarId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public boolean isPublicAccessEnabled() { return publicAccessEnabled; }
    public void setPublicAccessEnabled(boolean publicAccessEnabled) { this.publicAccessEnabled = publicAccessEnabled; }
    public boolean isAvailable() { return available; }
}
