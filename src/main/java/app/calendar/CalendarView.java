package app.calendar;

import app.event.CalendarEvent;
import app.event.CalendarEventRow;
import app.event.CalendarEventService;
import app.membership.CalendarAccessService;
import app.membership.CalendarRole;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Named
@ViewScoped
public class CalendarView implements Serializable {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarEventService calendarEventService;

    @Inject
    private CalendarTimeService calendarTimeService;

    private Long calendarId;
    private String calendarName;
    private String calendarDescription;
    private String timeZone;
    private CalendarRole role;
    private boolean available;
    private List<CalendarEventRow> events = List.of();

    private Long selectedEventId;
    private Integer selectedEventVersion;
    private String eventTitle;
    private String eventDescription;
    private String eventLocation;
    private LocalDateTime eventStartAt;
    private LocalDateTime eventEndAt;
    private boolean eventAllDay;

    public void load() {
        try {
            AppUser actor = currentUser.require();
            role = calendarAccessService.findActiveRole(actor, calendarId)
                    .orElseThrow(() -> new NotFoundException("Calendar was not found."));
            Calendar calendar = calendarService.requireActiveCalendar(calendarId);
            calendarName = calendar.getName();
            calendarDescription = calendar.getDescription();
            timeZone = calendar.getTimezone();
            available = true;
            reloadEvents(actor);
            resetEventForm();
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void createEvent() {
        try {
            AppUser actor = currentUser.require();
            calendarEventService.createEvent(
                    actor,
                    calendarId,
                    eventTitle,
                    eventDescription,
                    eventLocation,
                    calendarTimeService.toStoredTime(eventStartAt, timeZone),
                    calendarTimeService.toStoredTime(eventEndAt, timeZone),
                    eventAllDay);
            reloadEvents(actor);
            resetEventForm();
            addMessage(FacesMessage.SEVERITY_INFO, "Event created.", "The event is now on the calendar.");
        } catch (AuthorizationException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be created.", exception.getMessage());
        }
    }

    public void selectEvent(Long eventId) {
        CalendarEventRow event = events.stream()
                .filter(candidate -> candidate.getId().equals(eventId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Event was not found."));
        selectedEventId = event.getId();
        selectedEventVersion = event.getVersion();
        eventTitle = event.getTitle();
        eventDescription = event.getDescription();
        eventLocation = event.getLocation();
        eventStartAt = event.getStartAt();
        eventEndAt = event.getEndAt();
        eventAllDay = event.isAllDay();
    }

    public void updateEvent() {
        try {
            if (selectedEventId == null || selectedEventVersion == null) {
                throw new ValidationException("Select an event to edit.");
            }
            AppUser actor = currentUser.require();
            calendarEventService.updateEvent(
                    actor,
                    selectedEventId,
                    selectedEventVersion,
                    eventTitle,
                    eventDescription,
                    eventLocation,
                    calendarTimeService.toStoredTime(eventStartAt, timeZone),
                    calendarTimeService.toStoredTime(eventEndAt, timeZone),
                    eventAllDay);
            reloadEvents(actor);
            resetEventForm();
            addMessage(FacesMessage.SEVERITY_INFO, "Event updated.", "Your changes were saved.");
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be updated.", exception.getMessage());
        }
    }

    public void deleteEvent(Long eventId, Integer eventVersion) {
        try {
            AppUser actor = currentUser.require();
            calendarEventService.deleteEvent(actor, eventId, eventVersion);
            reloadEvents(actor);
            if (eventId.equals(selectedEventId)) {
                resetEventForm();
            }
            addMessage(FacesMessage.SEVERITY_INFO, "Event deleted.", "The event was removed.");
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be deleted.", exception.getMessage());
        }
    }

    public void resetEventForm() {
        selectedEventId = null;
        selectedEventVersion = null;
        eventTitle = null;
        eventDescription = null;
        eventLocation = null;
        LocalDateTime nextHour = LocalDateTime.now(java.time.ZoneId.of(timeZone == null ? "UTC" : timeZone))
                .plusHours(1)
                .truncatedTo(ChronoUnit.HOURS);
        eventStartAt = nextHour;
        eventEndAt = nextHour.plusHours(1);
        eventAllDay = false;
    }

    private void reloadEvents(AppUser actor) {
        events = calendarEventService.findMemberEvents(actor, calendarId, null, null).stream()
                .map(event -> CalendarEventRow.from(event, timeZone, calendarTimeService))
                .toList();
    }

    private void markNotFound() {
        available = false;
        FacesContext facesContext = FacesContext.getCurrentInstance();
        facesContext.getExternalContext().setResponseStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public Long getCalendarId() { return calendarId; }
    public void setCalendarId(Long calendarId) { this.calendarId = calendarId; }
    public String getCalendarName() { return calendarName; }
    public String getCalendarDescription() { return calendarDescription; }
    public String getTimeZone() { return timeZone; }
    public CalendarRole getRole() { return role; }
    public boolean isAvailable() { return available; }
    public boolean isEditable() { return role == CalendarRole.EDITOR || role == CalendarRole.ADMIN; }
    public boolean isAdmin() { return role == CalendarRole.ADMIN; }
    public List<CalendarEventRow> getEvents() { return events; }
    public Long getSelectedEventId() { return selectedEventId; }
    public boolean isEditingEvent() { return selectedEventId != null; }
    public String getEventTitle() { return eventTitle; }
    public void setEventTitle(String eventTitle) { this.eventTitle = eventTitle; }
    public String getEventDescription() { return eventDescription; }
    public void setEventDescription(String eventDescription) { this.eventDescription = eventDescription; }
    public String getEventLocation() { return eventLocation; }
    public void setEventLocation(String eventLocation) { this.eventLocation = eventLocation; }
    public LocalDateTime getEventStartAt() { return eventStartAt; }
    public void setEventStartAt(LocalDateTime eventStartAt) { this.eventStartAt = eventStartAt; }
    public LocalDateTime getEventEndAt() { return eventEndAt; }
    public void setEventEndAt(LocalDateTime eventEndAt) { this.eventEndAt = eventEndAt; }
    public boolean isEventAllDay() { return eventAllDay; }
    public void setEventAllDay(boolean eventAllDay) { this.eventAllDay = eventAllDay; }
}
