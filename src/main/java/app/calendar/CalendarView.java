package app.calendar;

import app.event.CalendarEvent;
import app.event.CalendarEventRow;
import app.event.CalendarEventService;
import app.membership.CalendarAccessService;
import app.membership.CalendarRole;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private String publicToken;
    private Integer calendarVersion;
    private String calendarName;
    private String calendarDescription;
    private String timeZone;
    private CalendarRole role;
    private boolean publicAccessEnabled;
    private boolean available;
    private List<CalendarEventRow> events = List.of();

    private Long selectedEventId;
    private Integer selectedEventVersion;
    private String eventTitle;
    private String eventDescription;
    private String eventLocation;
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;
    private LocalDate eventStartDate;
    private LocalDate eventEndDate;
    private boolean eventAllDay;
    private boolean eventAllDaySelection;

    public void load() {
        try {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
            if (Boolean.TRUE.equals(request.getAttribute(CalendarRouteServlet.NOT_FOUND_REQUEST_ATTRIBUTE))) {
                throw new NotFoundException("Calendar was not found.");
            }

            publicToken = (String) request.getAttribute(CalendarRouteServlet.CALENDAR_TOKEN_REQUEST_ATTRIBUTE);
            ApplicationUser actingUser = currentUser.find().orElse(null);
            Calendar calendar = (Calendar) request.getAttribute(CalendarRouteServlet.CALENDAR_REQUEST_ATTRIBUTE);
            if (calendar == null) {
                calendar = calendarAccessService.requireCalendarReadableByToken(actingUser, publicToken);
            }
            calendarId = calendar.getId();
            calendarVersion = calendar.getVersion();
            calendarName = calendar.getName();
            calendarDescription = calendar.getDescription();
            timeZone = calendar.getTimeZone();
            publicAccessEnabled = calendar.isPublicAccessEnabled();
            role = actingUser == null
                    ? null
                    : calendarAccessService.findActiveRole(actingUser, calendarId).orElse(null);
            available = true;
            reloadEvents(actingUser);
            resetEventForm();
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void regenerateCalendarLink() throws IOException {
        try {
            Calendar calendar = calendarService.regeneratePublicToken(
                    currentUser.require(), calendarId, calendarVersion);
            publicToken = calendar.getPublicToken();
            calendarVersion = calendar.getVersion();
            FacesContext facesContext = FacesContext.getCurrentInstance();
            facesContext.getExternalContext().redirect(
                    facesContext.getExternalContext().getRequestContextPath() + "/calendar/" + publicToken);
            facesContext.responseComplete();
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Calendar link could not be regenerated.", exception.getMessage());
        }
    }

    public void createEvent() {
        try {
            applyEventAllDaySelection();
            ApplicationUser actingUser = currentUser.require();
            calendarEventService.createEvent(
                    actingUser,
                    calendarId,
                    eventTitle,
                    eventDescription,
                    eventLocation,
                    toSubmittedTime(eventStartTime, eventStartDate),
                    toSubmittedTime(eventEndTime, eventEndDate),
                    eventAllDay);
            reloadEvents(actingUser);
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
        eventStartTime = event.getStartTime();
        eventEndTime = event.getEndTime();
        eventStartDate = event.getStartTime().toLocalDate();
        eventEndDate = event.getInclusiveEndDate();
        setEventAllDay(event.isAllDay());
    }

    public void updateEvent() {
        try {
            if (selectedEventId == null || selectedEventVersion == null) {
                throw new ValidationException("Select an event to edit.");
            }
            applyEventAllDaySelection();
            ApplicationUser actingUser = currentUser.require();
            calendarEventService.updateEvent(
                    actingUser,
                    selectedEventId,
                    selectedEventVersion,
                    eventTitle,
                    eventDescription,
                    eventLocation,
                    toSubmittedTime(eventStartTime, eventStartDate),
                    toSubmittedTime(eventEndTime, eventEndDate),
                    eventAllDay);
            reloadEvents(actingUser);
            resetEventForm();
            addMessage(FacesMessage.SEVERITY_INFO, "Event updated.", "Your changes were saved.");
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be updated.", exception.getMessage());
        }
    }

    public void deleteEvent(Long eventId, Integer eventVersion) {
        try {
            ApplicationUser actingUser = currentUser.require();
            calendarEventService.deleteEvent(actingUser, eventId, eventVersion);
            reloadEvents(actingUser);
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
        LocalDateTime nextHour = LocalDateTime.now(ZoneId.of(timeZone == null ? "UTC" : timeZone))
                .plusHours(1)
                .truncatedTo(ChronoUnit.HOURS);
        eventStartTime = nextHour;
        eventEndTime = nextHour.plusHours(1);
        eventStartDate = nextHour.toLocalDate();
        eventEndDate = nextHour.toLocalDate();
        setEventAllDay(false);
    }

    public void changeEventAllDayMode() {
        eventAllDay = eventAllDaySelection;
        if (eventAllDay) {
            LocalDate firstDay = eventStartTime == null ? null : eventStartTime.toLocalDate();
            LocalDate lastDay = inclusiveEndDateForTimedRange(firstDay);
            if (firstDay != null) {
                eventStartDate = firstDay;
            }
            if (lastDay != null) {
                eventEndDate = lastDay;
            }
            return;
        }

        if (eventStartDate != null) {
            eventStartTime = eventStartDate.atStartOfDay();
        }
        if (eventEndDate != null) {
            eventEndTime = eventEndDate.plusDays(1).atStartOfDay();
        }
    }

    private void applyEventAllDaySelection() {
        if (eventAllDay != eventAllDaySelection) {
            changeEventAllDayMode();
        }
    }

    private LocalDate inclusiveEndDateForTimedRange(LocalDate firstDay) {
        if (eventEndTime == null) {
            return null;
        }

        LocalDate inclusiveEndDate = eventEndTime.toLocalDate();
        if (eventEndTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                && eventStartTime != null
                && eventEndTime.isAfter(eventStartTime)) {
            LocalDate previousDay = inclusiveEndDate.minusDays(1);
            if (firstDay == null || !previousDay.isBefore(firstDay)) {
                return previousDay;
            }
        }
        return inclusiveEndDate;
    }

    private OffsetDateTime toSubmittedTime(LocalDateTime timedValue, LocalDate allDayDate) {
        if (eventAllDay) {
            return calendarTimeService.toStoredStartOfDay(allDayDate, timeZone);
        }
        return calendarTimeService.toStoredTime(timedValue, timeZone);
    }

    private void reloadEvents(ApplicationUser actingUser) {
        List<CalendarEvent> loadedEvents = role == null
                ? calendarEventService.findPublicEvents(publicToken, null, null)
                : calendarEventService.findEditorEvents(actingUser, calendarId, null, null);
        events = loadedEvents.stream()
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
    public boolean isPublicAccessEnabled() { return publicAccessEnabled; }
    public boolean isAvailable() { return available; }
    public boolean isEditable() { return role != null; }
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
    public LocalDateTime getEventStartTime() { return eventStartTime; }
    public void setEventStartTime(LocalDateTime eventStartTime) { this.eventStartTime = eventStartTime; }
    public LocalDateTime getEventEndTime() { return eventEndTime; }
    public void setEventEndTime(LocalDateTime eventEndTime) { this.eventEndTime = eventEndTime; }
    public LocalDate getEventStartDate() { return eventStartDate; }
    public void setEventStartDate(LocalDate eventStartDate) { this.eventStartDate = eventStartDate; }
    public LocalDate getEventEndDate() { return eventEndDate; }
    public void setEventEndDate(LocalDate eventEndDate) { this.eventEndDate = eventEndDate; }
    public boolean isEventAllDay() { return eventAllDay; }
    public void setEventAllDay(boolean eventAllDay) {
        this.eventAllDay = eventAllDay;
        this.eventAllDaySelection = eventAllDay;
    }
    public boolean isEventAllDaySelection() { return eventAllDaySelection; }
    public void setEventAllDaySelection(boolean eventAllDaySelection) {
        this.eventAllDaySelection = eventAllDaySelection;
    }
}
