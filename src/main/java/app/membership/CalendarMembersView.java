package app.membership;

import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.security.CurrentUser;
import app.user.AppUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
    private boolean available;
    private List<MemberRow> members = List.of();

    public void load() {
        try {
            AppUser actor = currentUser.require();
            currentUserId = actor.getId();
            Calendar calendar = calendarService.requireActiveCalendar(calendarId);
            calendarName = calendar.getName();
            reloadMembers(actor);
            available = true;
        } catch (AuthorizationException | NotFoundException exception) {
            markNotFound();
        }
    }

    public void saveRole(Long userId, CalendarRole role) {
        try {
            AppUser actor = currentUser.require();
            calendarMembershipService.changeMemberRole(actor, calendarId, userId, role);
            reloadMembers(actor);
            addMessage(FacesMessage.SEVERITY_INFO, "Member role saved.", "The member's access has been updated.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Member role could not be saved.", exception.getMessage());
        }
    }

    public void disableMember(Long userId) throws IOException {
        try {
            AppUser actor = currentUser.require();
            calendarMembershipService.disableMember(actor, calendarId, userId);
            if (actor.getId().equals(userId)) {
                FacesContext facesContext = FacesContext.getCurrentInstance();
                facesContext.getExternalContext().redirect(
                        facesContext.getExternalContext().getRequestContextPath() + "/app/calendars");
                facesContext.responseComplete();
                return;
            }
            reloadMembers(actor);
            addMessage(FacesMessage.SEVERITY_INFO, "Member access removed.", "The member can no longer open this calendar.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Member access could not be removed.", exception.getMessage());
        }
    }

    private void reloadMembers(AppUser actor) {
        members = calendarMembershipService.listMembers(actor, calendarId).stream()
                .map(member -> new MemberRow(
                        member.getUser().getId(),
                        member.getUser().getDisplayName(),
                        member.getUser().getUsername(),
                        member.getRole(),
                        member.isActive(),
                        member.getUser().getId().equals(currentUserId)))
                .toList();
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
