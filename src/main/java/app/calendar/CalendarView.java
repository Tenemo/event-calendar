package app.calendar;

import app.event.CalendarEvent;
import app.event.CalendarEventCursor;
import app.event.CalendarEventPage;
import app.event.CalendarEventRevision;
import app.event.CalendarEventRow;
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
import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Named
@ViewScoped
public class CalendarView implements Serializable {
    private static final int EVENT_PAGE_SIZE = 50;
    private static final int MAXIMUM_EVENT_PAGE_RESTARTS = 3;
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
    private List<CalendarEventRow> events = List.of();
    private boolean moreEventsAvailable;
    private CalendarEventCursor eventCursor;
    private CalendarEventRevision eventRevision;
    private Long firstNewlyLoadedEventId;
    private String eventPaginationAnnouncement;

    private final EventFormState eventForm = new EventFormState();

    public void load() {
        try {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
            if (Boolean.TRUE.equals(request.getAttribute(CalendarRouteFilter.CALENDAR_NOT_FOUND_REQUEST_ATTRIBUTE))) {
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
            if (!reloadEvents(actingUser)) {
                addMessage(
                        FacesMessage.SEVERITY_WARN,
                        "Events could not be loaded.",
                        "Events kept changing while they were loading. Reload the page to try again.");
            }
            markAnonymousCalendarPostbackRequirement(actingUser);
            resetEventForm();
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void regenerateCalendarLink() throws IOException {
        ApplicationUser actingUser = null;
        try {
            actingUser = currentUser.require();
            Calendar calendar = calendarService.regenerateCalendarLink(
                    actingUser, calendarId, calendarVersion);
            calendarLinkToken = calendar.getCalendarLinkToken();
            calendarVersion = calendar.getVersion();
            FacesContext facesContext = FacesContext.getCurrentInstance();
            RelativeRedirect.send(facesContext, "/" + calendarLinkToken);
        } catch (AuthorizationException | ConflictException | NotFoundException exception) {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            addMessage(FacesMessage.SEVERITY_ERROR, "Calendar link could not be regenerated.", exception.getMessage());
            RelativeRedirect.sendKeepingMessages(
                    facesContext,
                    currentMemberCalendarRoute(actingUser));
        }
    }

    String currentMemberCalendarRoute(ApplicationUser actingUser) {
        if (actingUser == null || calendarId == null) {
            return MEMBER_CALENDARS_ROUTE;
        }
        return calendarService.findCalendarsForUser(actingUser).stream()
                .filter(calendarMembership ->
                        calendarId.equals(calendarMembership.getCalendarId()))
                .map(CalendarMembershipSummary::getCalendarLinkToken)
                .filter(CalendarLinkToken::isValid)
                .map(calendarToken -> "/" + calendarToken)
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
        eventForm.select(event);
    }

    public void updateEvent() {
        ApplicationUser actingUser;
        try {
            if (eventForm.getSelectedEventId() == null
                    || eventForm.getSelectedEventVersion() == null) {
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

        if (eventId != null && eventId.equals(eventForm.getSelectedEventId())) {
            resetEventForm();
        }
        addMessage(FacesMessage.SEVERITY_INFO, "Event deleted.", "The event was removed.");
        reloadEventsAfterCommittedChange(actingUser);
    }

    public void loadMoreEvents() {
        ApplicationUser actingUser = currentUser.find().orElse(null);
        List<CalendarEventRow> previouslyLoadedEvents = events;
        firstNewlyLoadedEventId = null;
        eventPaginationAnnouncement = null;
        try {
            CalendarEventPage eventPage = loadEventPage(
                    actingUser,
                    eventCursor,
                    eventRevision);
            if (eventPage.restartRequired()) {
                if (!reloadEvents(
                        actingUser,
                        previouslyLoadedEvents.size() + EVENT_PAGE_SIZE)) {
                    eventPaginationAnnouncement =
                            "Events kept changing and could not be refreshed. Try again.";
                    addMessage(
                            FacesMessage.SEVERITY_WARN,
                            "Events could not be refreshed.",
                            "Events kept changing while they were loading. Try again.");
                    return;
                }
                describePaginationResult(previouslyLoadedEvents, true);
                return;
            }
            List<CalendarEventRow> expandedEvents = new ArrayList<>(events);
            expandedEvents.addAll(toEventRows(eventPage.events()));
            events = List.copyOf(expandedEvents);
            moreEventsAvailable = eventPage.hasMore();
            eventCursor = eventPage.nextCursor();
            eventRevision = eventPage.revision();
            describePaginationResult(previouslyLoadedEvents, false);
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        } finally {
            markAnonymousCalendarPostbackRequirement(actingUser);
        }
    }

    public void resetEventForm() {
        eventForm.reset(timeZone);
    }

    public void changeEventAllDayMode() {
        eventForm.changeAllDayMode(timeZone);
    }

    private boolean reloadEvents(ApplicationUser actingUser) {
        return reloadEvents(actingUser, Math.max(EVENT_PAGE_SIZE, events.size()));
    }

    private boolean reloadEvents(ApplicationUser actingUser, int minimumNumberOfEvents) {
        int eventsToReload = Math.max(EVENT_PAGE_SIZE, minimumNumberOfEvents);
        for (int restartCount = 0;
                restartCount < MAXIMUM_EVENT_PAGE_RESTARTS;
                restartCount++) {
            List<CalendarEventRow> reloadedEvents = new ArrayList<>();
            CalendarEventCursor reloadCursor = null;
            CalendarEventRevision reloadRevision = null;
            CalendarEventPage eventPage;
            boolean restartRequired = false;
            do {
                eventPage = loadEventPage(
                        actingUser,
                        reloadCursor,
                        reloadRevision);
                if (eventPage.restartRequired()) {
                    restartRequired = true;
                    break;
                }
                reloadRevision = eventPage.revision();
                reloadedEvents.addAll(toEventRows(eventPage.events()));
                reloadCursor = eventPage.nextCursor();
            } while (eventPage.hasMore() && reloadedEvents.size() < eventsToReload);
            if (restartRequired) {
                continue;
            }

            events = List.copyOf(reloadedEvents);
            moreEventsAvailable = eventPage.hasMore();
            eventCursor = reloadCursor;
            eventRevision = reloadRevision;
            return true;
        }
        return false;
    }

    private CalendarEventPage loadEventPage(
            ApplicationUser actingUser,
            CalendarEventCursor afterCursor,
            CalendarEventRevision expectedRevision) {
        return role == null
                ? calendarEventService.findPublicEvents(
                        calendarLinkToken,
                        afterCursor,
                        expectedRevision,
                        EVENT_PAGE_SIZE)
                : calendarEventService.findEventsForMember(
                        actingUser,
                        calendarId,
                        afterCursor,
                        expectedRevision,
                        EVENT_PAGE_SIZE);
    }

    void describePaginationResult(
            List<CalendarEventRow> previouslyLoadedEvents,
            boolean refreshed) {
        Set<Long> previouslyLoadedEventIds = new HashSet<>();
        previouslyLoadedEvents.forEach(event -> previouslyLoadedEventIds.add(event.getId()));
        List<CalendarEventRow> newlyVisibleEvents = events.stream()
                .filter(event -> !previouslyLoadedEventIds.contains(event.getId()))
                .toList();
        firstNewlyLoadedEventId = newlyVisibleEvents.isEmpty()
                ? null
                : newlyVisibleEvents.getFirst().getId();
        if (refreshed) {
            eventPaginationAnnouncement = newlyVisibleEvents.isEmpty()
                    ? "Events changed, so the list was refreshed. "
                            + "Total events shown: " + events.size() + "."
                    : "Events changed, so the list was refreshed. "
                            + "New events shown: " + newlyVisibleEvents.size() + ". "
                            + "Total events shown: " + events.size() + ".";
            return;
        }
        eventPaginationAnnouncement = "Loaded "
                + newlyVisibleEvents.size()
                + " more events. "
                + "Total events shown: "
                + events.size()
                + ".";
    }

    private void markAnonymousCalendarPostbackRequirement(ApplicationUser actingUser) {
        if (actingUser != null) {
            return;
        }
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance()
                .getExternalContext()
                .getRequest();
        boolean postbackRequired = !"HEAD".equalsIgnoreCase(request.getMethod())
                && available
                && moreEventsAvailable;
        request.setAttribute(
                CalendarRouteFilter.ANONYMOUS_CALENDAR_POSTBACK_REQUIRED_REQUEST_ATTRIBUTE,
                postbackRequired);
    }

    private List<CalendarEventRow> toEventRows(List<CalendarEvent> loadedEvents) {
        return loadedEvents.stream()
                .map(event -> CalendarEventRow.from(event, timeZone, calendarTimeService))
                .toList();
    }

    private void reloadEventsAfterCommittedChange(ApplicationUser actingUser) {
        try {
            if (!reloadEvents(actingUser)) {
                addMessage(
                        FacesMessage.SEVERITY_WARN,
                        "The change was saved, but the page could not be refreshed.",
                        "Events kept changing while they were loading. Open the calendar again to see its current events.");
            }
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
        FacesMessages.add(severity, summary, detail);
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
    public boolean isMoreEventsAvailable() { return moreEventsAvailable; }
    public Long getFirstNewlyLoadedEventId() { return firstNewlyLoadedEventId; }
    public String getEventPaginationAnnouncement() { return eventPaginationAnnouncement; }
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
    public void setEventAllDaySelection(boolean eventAllDaySelection) {
        eventForm.setAllDaySelection(eventAllDaySelection);
    }
}
