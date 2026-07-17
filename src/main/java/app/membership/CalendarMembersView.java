package app.membership;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.List;

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
    private Long currentUserId;
    private String calendarName;
    private String calendarLinkToken;
    private boolean available;
    private List<MemberRow> members = List.of();

    public void load() {
        try {
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
        updateMemberRole(userId, role, false);
    }

    public void reactivateMembership(Long userId, CalendarRole role) {
        updateMemberRole(userId, role, true);
    }

    private void updateMemberRole(Long userId, CalendarRole role, boolean reactivationRequested) {
        ApplicationUser actingUser;
        try {
            actingUser = currentUser.require();
            if (reactivationRequested) {
                calendarMembershipService.reactivateMembership(actingUser, calendarId, userId, role);
            } else {
                calendarMembershipService.changeMemberRole(actingUser, calendarId, userId, role);
            }
        } catch (ValidationException exception) {
            reloadMembersAfterRejectedChange();
            addMessage(
                    FacesMessage.SEVERITY_ERROR,
                    reactivationRequested
                            ? "Member access could not be reactivated."
                            : "Member role could not be saved.",
                    exception.getMessage());
            return;
        } catch (AuthorizationException | NotFoundException exception) {
            addMessage(
                    FacesMessage.SEVERITY_ERROR,
                    reactivationRequested
                            ? "Member access could not be reactivated."
                            : "Member role could not be saved.",
                    exception.getMessage());
            return;
        }

        addMessage(
                FacesMessage.SEVERITY_INFO,
                reactivationRequested ? "Member access reactivated." : "Member role saved.",
                reactivationRequested
                        ? "The member can edit this calendar again with the selected role."
                        : "The member's role has been updated.");
        reloadMembersAfterCommittedChange(actingUser);
    }

    public void removeMemberAccess(Long userId) {
        ApplicationUser actingUser;
        try {
            actingUser = currentUser.require();
            calendarMembershipService.disableMembership(actingUser, calendarId, userId);
        } catch (ValidationException exception) {
            reloadMembersAfterRejectedChange();
            addMessage(FacesMessage.SEVERITY_ERROR, "Member access could not be removed.", exception.getMessage());
            return;
        } catch (AuthorizationException | NotFoundException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Member access could not be removed.", exception.getMessage());
            return;
        }

        addMessage(
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
                        member.isActive(),
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
            addMessage(
                    FacesMessage.SEVERITY_WARN,
                    "The change was saved, but the page could not be refreshed.",
                    "Open the calendar again to see its current membership state.");
        }
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
    public String getCalendarName() { return calendarName; }
    public String getCalendarLinkToken() { return calendarLinkToken; }
    public boolean isAvailable() { return available; }
    public List<MemberRow> getMembers() { return members; }
    public CalendarRole[] getRoles() { return CalendarRole.values(); }

    public static final class MemberRow implements Serializable {
        private final Long userId;
        private final String displayName;
        private final String username;
        private final boolean active;
        private final boolean currentUser;
        private CalendarRole role;

        private MemberRow(
                Long userId,
                String displayName,
                String username,
                CalendarRole role,
                boolean active,
                boolean currentUser) {
            this.userId = userId;
            this.displayName = displayName;
            this.username = username;
            this.role = role;
            this.active = active;
            this.currentUser = currentUser;
        }

        public Long getUserId() { return userId; }
        public String getDisplayName() { return displayName; }
        public String getUsername() { return username; }
        public CalendarRole getRole() { return role; }
        public void setRole(CalendarRole role) { this.role = role; }
        public boolean isActive() { return active; }
        public boolean isCurrentUser() { return currentUser; }
    }
}
