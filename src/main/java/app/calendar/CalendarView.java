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
import jakarta.servlet.http.HttpServletResponse;
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
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;
    private LocalDate eventStartDate;
    private LocalDate eventEndDate;
    private boolean eventAllDay;

    public void load() {
        try {
            ApplicationUser actingUser = currentUser.require();
            role = calendarAccessService.findActiveRole(actingUser, calendarId)
                    .orElseThrow(() -> new NotFoundException("Calendar was not found."));
            Calendar calendar = calendarService.requireActiveCalendar(calendarId);
            calendarName = calendar.getName();
            calendarDescription = calendar.getDescription();
            timeZone = calendar.getTimeZone();
            available = true;
            reloadEvents(actingUser);
            resetEventForm();
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void createEvent() {
        try {
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
        eventAllDay = event.isAllDay();
    }

    public void updateEvent() {
        try {
            if (selectedEventId == null || selectedEventVersion == null) {
                throw new ValidationException("Select an event to edit.");
            }
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
        eventAllDay = false;
    }

    public void changeEventAllDayMode() {
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
        events = calendarEventService.findMemberEvents(actingUser, calendarId, null, null).stream()
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
    public LocalDateTime getEventStartTime() { return eventStartTime; }
    public void setEventStartTime(LocalDateTime eventStartTime) { this.eventStartTime = eventStartTime; }
    public LocalDateTime getEventEndTime() { return eventEndTime; }
    public void setEventEndTime(LocalDateTime eventEndTime) { this.eventEndTime = eventEndTime; }
    public LocalDate getEventStartDate() { return eventStartDate; }
    public void setEventStartDate(LocalDate eventStartDate) { this.eventStartDate = eventStartDate; }
    public LocalDate getEventEndDate() { return eventEndDate; }
    public void setEventEndDate(LocalDate eventEndDate) { this.eventEndDate = eventEndDate; }
    public boolean isEventAllDay() { return eventAllDay; }
    public void setEventAllDay(boolean eventAllDay) { this.eventAllDay = eventAllDay; }
}
