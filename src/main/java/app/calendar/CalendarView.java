package app.calendar;

import app.event.CalendarEvent;
import app.event.CalendarEventItem;
import app.event.CalendarEventService;
import app.event.EventFormState;
import app.membership.CalendarAccessService;
import app.membership.CalendarRole;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ConflictException;
import app.util.NotFoundException;
import app.util.ValidationException;
import app.web.FacesMessages;
import app.web.RelativeRedirect;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Named
@ViewScoped
public class CalendarView implements Serializable {
    private static final String MEMBER_CALENDARS_ROUTE = "/app/calendars";

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
    private List<CalendarEventItem> events = List.of();
    private final EventFormState eventForm = new EventFormState();

    public void load() {
        try {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
            if (Boolean.TRUE.equals(request.getAttribute(
                    CalendarRouteFilter.CALENDAR_NOT_FOUND_REQUEST_ATTRIBUTE))) {
                throw new NotFoundException("Calendar was not found.");
            }

            calendarLinkToken = (String) request.getAttribute(
                    CalendarRouteFilter.CALENDAR_LINK_TOKEN_REQUEST_ATTRIBUTE);
            ApplicationUser actingUser = currentUser.find().orElse(null);
            Calendar calendar = (Calendar) request.getAttribute(
                    CalendarRouteFilter.CALENDAR_REQUEST_ATTRIBUTE);
            if (calendar == null) {
                calendar = calendarAccessService.requireCalendarReadableByLinkToken(
                        actingUser, calendarLinkToken);
            }
            calendarId = calendar.getId();
            calendarVersion = calendar.getVersion();
            calendarName = calendar.getName();
            calendarDescription = calendar.getDescription();
            timeZone = calendar.getTimeZone();
            publicAccessEnabled = calendar.isPublicAccessEnabled();
            role = actingUser == null
                    ? null
                    : calendarAccessService.findRole(actingUser, calendarId).orElse(null);
            available = true;
            reloadEvents(actingUser);
            resetEventForm();
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void regenerateCalendarLink() {
        ApplicationUser actingUser = null;
        try {
            actingUser = currentUser.require();
            Calendar calendar = calendarService.regenerateCalendarLink(
                    actingUser, calendarId, calendarVersion);
            calendarLinkToken = calendar.getCalendarLinkToken();
            calendarVersion = calendar.getVersion();
            RelativeRedirect.send(FacesContext.getCurrentInstance(), "/" + calendarLinkToken);
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Calendar link could not be regenerated.",
                    exception.getMessage());
            RelativeRedirect.sendKeepingMessages(
                    FacesContext.getCurrentInstance(), currentMemberCalendarRoute(actingUser));
        }
    }

    private String currentMemberCalendarRoute(ApplicationUser actingUser) {
        if (actingUser == null || calendarId == null) {
            return MEMBER_CALENDARS_ROUTE;
        }
        return calendarService.findCalendarsForUser(actingUser).stream()
                .filter(membership -> calendarId.equals(membership.getCalendarId()))
                .map(CalendarMembershipSummary::getCalendarLinkToken)
                .filter(CalendarLinkToken::isValid)
                .map(token -> "/" + token)
                .findFirst()
                .orElse(MEMBER_CALENDARS_ROUTE);
    }

    public void createEvent() {
        ApplicationUser actingUser;
        try {
            eventForm.applyAllDaySelection(timeZone);
            actingUser = currentUser.require();
            calendarEventService.createEvent(
                    actingUser,
                    calendarId,
                    eventForm.getTitle(),
                    eventForm.getDescription(),
                    eventForm.getLocation(),
                    eventForm.toTimeInput(),
                    calendarVersion,
                    timeZone);
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR, "Event could not be created.", exception.getMessage());
            return;
        }

        resetEventForm();
        FacesMessages.add(FacesMessage.SEVERITY_INFO, "Event created.", "The event is now on the calendar.");
        reloadEventsAfterChange(actingUser);
    }

    public void selectEvent(Long eventId) {
        events.stream()
                .filter(event -> event.getId().equals(eventId))
                .findFirst()
                .ifPresentOrElse(
                        eventForm::select,
                        () -> FacesMessages.add(
                                FacesMessage.SEVERITY_ERROR,
                                "Event could not be selected.",
                                "The event is no longer available. Reload the page and try again."));
    }

    public void updateEvent() {
        ApplicationUser actingUser;
        try {
            if (eventForm.getSelectedEventId() == null || eventForm.getSelectedEventVersion() == null) {
                throw new ValidationException("Select an event to edit.");
            }
            eventForm.applyAllDaySelection(timeZone);
            actingUser = currentUser.require();
            calendarEventService.updateEvent(
                    actingUser,
                    eventForm.getSelectedEventId(),
                    eventForm.getSelectedEventVersion(),
                    eventForm.getTitle(),
                    eventForm.getDescription(),
                    eventForm.getLocation(),
                    eventForm.toTimeInput(),
                    calendarVersion,
                    timeZone);
        } catch (AuthorizationException | ConflictException | NotFoundException | ValidationException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR, "Event could not be updated.", exception.getMessage());
            return;
        }

        resetEventForm();
        FacesMessages.add(FacesMessage.SEVERITY_INFO, "Event updated.", "Your changes were saved.");
        reloadEventsAfterChange(actingUser);
    }

    public void deleteEvent(Long eventId, Integer eventVersion) {
        ApplicationUser actingUser;
        try {
            actingUser = currentUser.require();
            calendarEventService.deleteEvent(actingUser, eventId, eventVersion);
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR, "Event could not be deleted.", exception.getMessage());
            return;
        }

        if (eventId != null && eventId.equals(eventForm.getSelectedEventId())) {
            resetEventForm();
        }
        FacesMessages.add(FacesMessage.SEVERITY_INFO, "Event deleted.", "The event was removed.");
        reloadEventsAfterChange(actingUser);
    }

    public void resetEventForm() {
        eventForm.reset(timeZone);
    }

    public void changeEventAllDayMode() {
        eventForm.changeAllDayMode(timeZone);
    }

    private void reloadEvents(ApplicationUser actingUser) {
        List<CalendarEvent> loadedEvents = role == null
                ? calendarEventService.findPublicEvents(calendarLinkToken)
                : calendarEventService.findEventsForMember(actingUser, calendarId);
        events = loadedEvents.stream()
                .map(event -> CalendarEventItem.from(event, timeZone, calendarTimeService))
                .toList();
    }

    private void reloadEventsAfterChange(ApplicationUser actingUser) {
        try {
            reloadEvents(actingUser);
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
            FacesMessages.add(
                    FacesMessage.SEVERITY_WARN,
                    "The change was saved, but the page could not be refreshed.",
                    "Open the calendar again to see its current events.");
        }
    }

    private void markNotFound() {
        available = false;
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!facesContext.isPostback()) {
            facesContext.getExternalContext().setResponseStatus(HttpServletResponse.SC_NOT_FOUND);
        }
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
    public List<CalendarEventItem> getEvents() { return events; }
    public boolean isEditingEvent() { return eventForm.isEditing(); }
    public String getEventTitle() { return eventForm.getTitle(); }
    public void setEventTitle(String eventTitle) { eventForm.setTitle(eventTitle); }
    public String getEventDescription() { return eventForm.getDescription(); }
    public void setEventDescription(String eventDescription) { eventForm.setDescription(eventDescription); }
    public String getEventLocation() { return eventForm.getLocation(); }
    public void setEventLocation(String eventLocation) { eventForm.setLocation(eventLocation); }
    public LocalDateTime getEventStartTime() { return eventForm.getStartTime(); }
    public void setEventStartTime(LocalDateTime eventStartTime) { eventForm.setStartTime(eventStartTime); }
    public LocalDateTime getEventEndTime() { return eventForm.getEndTime(); }
    public void setEventEndTime(LocalDateTime eventEndTime) { eventForm.setEndTime(eventEndTime); }
    public LocalDate getEventFirstDay() { return eventForm.getFirstDay(); }
    public void setEventFirstDay(LocalDate eventFirstDay) { eventForm.setFirstDay(eventFirstDay); }
    public LocalDate getEventLastDay() { return eventForm.getLastDay(); }
    public void setEventLastDay(LocalDate eventLastDay) { eventForm.setLastDay(eventLastDay); }
    public boolean isEventAllDay() { return eventForm.isAllDay(); }
    public boolean isEventAllDaySelection() { return eventForm.isAllDaySelection(); }
    public void setEventAllDaySelection(boolean selected) { eventForm.setAllDaySelection(selected); }
}
