package app.membership;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import app.web.FacesMessages;
import app.web.ViewParameterParser;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.List;
import java.util.OptionalLong;

@Named
@ViewScoped
public class CalendarMembersView implements Serializable {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarMembershipService calendarMembershipService;

    private Long calendarId;
    private String calendarIdParameter;
    private Long currentUserId;
    private String calendarName;
    private String calendarLinkToken;
    private boolean available;
    private List<MemberRow> members = List.of();

    public void load() {
        try {
            OptionalLong parsedCalendarId = ViewParameterParser.positiveLong(calendarIdParameter);
            if (parsedCalendarId.isEmpty()) {
                throw new NotFoundException("Calendar was not found.");
            }
            calendarId = parsedCalendarId.getAsLong();
            ApplicationUser actingUser = currentUser.require();
            currentUserId = actingUser.getId();
            Calendar calendar = calendarService.requireAdminCalendar(actingUser, calendarId);
            calendarName = calendar.getName();
            calendarLinkToken = calendar.getCalendarLinkToken();
            reloadMembers(actingUser);
            available = true;
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void saveRole(Long userId, CalendarRole role) {
        ApplicationUser actingUser;
        try {
            actingUser = currentUser.require();
            calendarMembershipService.changeMemberRole(actingUser, calendarId, userId, role);
        } catch (ValidationException exception) {
            reloadMembersAfterRejectedChange();
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Member role could not be saved.",
                    exception.getMessage());
            return;
        } catch (AuthorizationException | NotFoundException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Member role could not be saved.",
                    exception.getMessage());
            return;
        }

        FacesMessages.add(
                FacesMessage.SEVERITY_INFO,
                "Member role saved.",
                "The member's role has been updated.");
        reloadMembersAfterCommittedChange(actingUser);
    }

    public void removeMemberAccess(Long userId) {
        ApplicationUser actingUser;
        try {
            actingUser = currentUser.require();
            calendarMembershipService.removeMembership(actingUser, calendarId, userId);
        } catch (ValidationException exception) {
            reloadMembersAfterRejectedChange();
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Member access could not be removed.",
                    exception.getMessage());
            return;
        } catch (AuthorizationException | NotFoundException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Member access could not be removed.",
                    exception.getMessage());
            return;
        }

        FacesMessages.add(
                FacesMessage.SEVERITY_INFO,
                "Member access removed.",
                "The member can no longer edit this calendar. Public access through the calendar link is unchanged.");
        reloadMembersAfterCommittedChange(actingUser);
    }

    private void reloadMembers(ApplicationUser actingUser) {
        members = calendarMembershipService.listMembers(actingUser, calendarId).stream()
                .map(member -> new MemberRow(
                        member.getUser().getId(),
                        member.getUser().getDisplayName(),
                        member.getUser().getUsername(),
                        member.getRole(),
                        member.getUser().getId().equals(currentUserId)))
                .toList();
    }

    private void reloadMembersAfterRejectedChange() {
        try {
            reloadMembers(currentUser.require());
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    private void reloadMembersAfterCommittedChange(ApplicationUser actingUser) {
        try {
            reloadMembers(actingUser);
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
            FacesMessages.add(
                    FacesMessage.SEVERITY_WARN,
                    "The change was saved, but the page could not be refreshed.",
                    "Open the calendar again to see its current membership state.");
        }
    }

    private void markNotFound() {
        available = false;
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!facesContext.isPostback()) {
            facesContext.getExternalContext().setResponseStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    public String getCalendarIdParameter() { return calendarIdParameter; }
    public void setCalendarIdParameter(String calendarIdParameter) { this.calendarIdParameter = calendarIdParameter; }
    public String getCalendarName() { return calendarName; }
    public String getCalendarLinkToken() { return calendarLinkToken; }
    public boolean isAvailable() { return available; }
    public List<MemberRow> getMembers() { return members; }
    public CalendarRole[] getRoles() { return CalendarRole.values(); }

    public static final class MemberRow implements Serializable {
        private final Long userId;
        private final String displayName;
        private final String username;
        private final boolean currentUser;
        private CalendarRole role;

        private MemberRow(
                Long userId,
                String displayName,
                String username,
                CalendarRole role,
                boolean currentUser) {
            this.userId = userId;
            this.displayName = displayName;
            this.username = username;
            this.role = role;
            this.currentUser = currentUser;
        }

        public Long getUserId() { return userId; }
        public String getDisplayName() { return displayName; }
        public String getUsername() { return username; }
        public CalendarRole getRole() { return role; }
        public void setRole(CalendarRole role) { this.role = role; }
        public boolean isCurrentUser() { return currentUser; }
    }
}
