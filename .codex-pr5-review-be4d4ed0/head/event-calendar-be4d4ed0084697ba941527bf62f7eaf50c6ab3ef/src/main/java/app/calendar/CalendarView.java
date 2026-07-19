package app.calendar;

import app.event.CalendarEvent;
import app.event.CalendarEventRow;
import app.event.CalendarEventService;
import app.event.EventTimeInput;
import app.membership.CalendarAccessService;
import app.membership.CalendarRole;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import app.web.RelativeRedirect;
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
    private String calendarLinkToken;
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
    private LocalDate eventFirstDay;
    private LocalDate eventLastDay;
    private boolean eventAllDay;
    private boolean eventAllDaySelection;

    public void load() {
        try {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
            if (Boolean.TRUE.equals(request.getAttribute(CalendarRouteFilter.NOT_FOUND_REQUEST_ATTRIBUTE))) {
                throw new NotFoundException("Calendar was not found.");
            }

            calendarLinkToken = (String) request.getAttribute(
                    CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE);
            ApplicationUser actingUser = currentUser.find().orElse(null);
            Calendar calendar = (Calendar) request.getAttribute(CalendarRouteFilter.CALENDAR_REQUEST_ATTRIBUTE);
            if (calendar == null) {
                calendar = calendarAccessService.requireCalendarReadableByLinkToken(actingUser, calendarLinkToken);
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
            Calendar calendar = calendarService.regenerateCalendarLink(
                    currentUser.require(), calendarId, calendarVersion);
            calendarLinkToken = calendar.getCalendarLinkToken();
            calendarVersion = calendar.getVersion();
            FacesContext facesContext = FacesContext.getCurrentInstance();
            RelativeRedirect.send(facesContext, "/" + calendarLinkToken);
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            addMessage(FacesMessage.SEVERITY_ERROR, "Calendar link could not be regenerated.", exception.getMessage());
            RelativeRedirect.sendKeepingMessages(facesContext, "/" + calendarLinkToken);
        }
    }

    public void createEvent() {
        ApplicationUser actingUser;
        try {
            applyEventAllDaySelection();
            actingUser = currentUser.require();
            calendarEventService.createEvent(
                    actingUser,
                    calendarId,
                    eventTitle,
                    eventDescription,
                    eventLocation,
                    eventTimeInput(),
                    calendarVersion,
                    timeZone);
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be created.", exception.getMessage());
            return;
        }

        resetEventForm();
        addMessage(FacesMessage.SEVERITY_INFO, "Event created.", "The event is now on the calendar.");
        reloadEventsAfterCommittedChange(actingUser);
    }

    public void selectEvent(Long eventId) {
        CalendarEventRow event = events.stream()
                .filter(candidate -> candidate.getId().equals(eventId))
                .findFirst()
                .orElse(null);
        if (event == null) {
            addMessage(
                    FacesMessage.SEVERITY_ERROR,
                    "Event could not be selected.",
                    "The event is no longer available. Reload the page and try again.");
            return;
        }
        selectedEventId = event.getId();
        selectedEventVersion = event.getVersion();
        eventTitle = event.getTitle();
        eventDescription = event.getDescription();
        eventLocation = event.getLocation();
        eventStartTime = event.getStartTime();
        eventEndTime = event.getEndTime();
        eventFirstDay = event.getStartTime().toLocalDate();
        eventLastDay = event.getInclusiveEndDate();
        setEventAllDay(event.isAllDay());
    }

    public void updateEvent() {
        ApplicationUser actingUser;
        try {
            if (selectedEventId == null || selectedEventVersion == null) {
                throw new ValidationException("Select an event to edit.");
            }
            applyEventAllDaySelection();
            actingUser = currentUser.require();
            calendarEventService.updateEvent(
                    actingUser,
                    selectedEventId,
                    selectedEventVersion,
                    eventTitle,
                    eventDescription,
                    eventLocation,
                    eventTimeInput(),
                    calendarVersion,
                    timeZone);
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be updated.", exception.getMessage());
            return;
        }

        resetEventForm();
        addMessage(FacesMessage.SEVERITY_INFO, "Event updated.", "Your changes were saved.");
        reloadEventsAfterCommittedChange(actingUser);
    }

    public void deleteEvent(Long eventId, Integer eventVersion) {
        ApplicationUser actingUser;
        try {
            actingUser = currentUser.require();
            calendarEventService.deleteEvent(actingUser, eventId, eventVersion);
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Event could not be deleted.", exception.getMessage());
            return;
        }

        if (eventId != null && eventId.equals(selectedEventId)) {
            resetEventForm();
        }
        addMessage(FacesMessage.SEVERITY_INFO, "Event deleted.", "The event was removed.");
        reloadEventsAfterCommittedChange(actingUser);
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
        eventFirstDay = nextHour.toLocalDate();
        eventLastDay = nextHour.toLocalDate();
        setEventAllDay(false);
    }

    public void changeEventAllDayMode() {
        eventAllDay = eventAllDaySelection;
        if (eventAllDay) {
            LocalDate firstDay = eventStartTime == null ? null : eventStartTime.toLocalDate();
            LocalDate lastDay = inclusiveEndDateForTimedRange(firstDay);
            if (firstDay != null) {
                eventFirstDay = firstDay;
            }
            if (lastDay != null) {
                eventLastDay = lastDay;
            }
            return;
        }

        if (eventFirstDay != null) {
            eventStartTime = eventFirstDay.atStartOfDay();
        }
        if (eventLastDay != null) {
            eventEndTime = eventLastDay.plusDays(1).atStartOfDay();
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

    private EventTimeInput eventTimeInput() {
        return eventAllDay
                ? new EventTimeInput.AllDay(eventFirstDay, eventLastDay)
                : new EventTimeInput.Timed(eventStartTime, eventEndTime);
    }

    private void reloadEvents(ApplicationUser actingUser) {
        List<CalendarEvent> loadedEvents = role == null
                ? calendarEventService.findPublicEvents(calendarLinkToken)
                : calendarEventService.findEventsForMember(actingUser, calendarId);
        events = loadedEvents.stream()
                .map(event -> CalendarEventRow.from(event, timeZone, calendarTimeService))
                .toList();
    }

    private void reloadEventsAfterCommittedChange(ApplicationUser actingUser) {
        try {
            reloadEvents(actingUser);
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
            addMessage(
                    FacesMessage.SEVERITY_WARN,
                    "The change was saved, but the page could not be refreshed.",
                    "Open the calendar again to see its current events.");
        }
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
    public String getCalendarName() { return calendarName; }
    public String getCalendarDescription() { return calendarDescription; }
    public String getTimeZone() { return timeZone; }
    public CalendarRole getRole() { return role; }
    public boolean isPublicAccessEnabled() { return publicAccessEnabled; }
    public boolean isAvailable() { return available; }
    public boolean isEditable() { return role != null; }
    public boolean isAdmin() { return role == CalendarRole.ADMIN; }
    public List<CalendarEventRow> getEvents() { return events; }
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
    public LocalDate getEventFirstDay() { return eventFirstDay; }
    public void setEventFirstDay(LocalDate eventFirstDay) { this.eventFirstDay = eventFirstDay; }
    public LocalDate getEventLastDay() { return eventLastDay; }
    public void setEventLastDay(LocalDate eventLastDay) { this.eventLastDay = eventLastDay; }
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
