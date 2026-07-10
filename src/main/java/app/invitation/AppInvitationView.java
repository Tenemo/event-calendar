package app.invitation;

import app.calendar.CalendarMembershipSummary;
import app.calendar.CalendarService;
import app.config.ApplicationUrlService;
import app.membership.CalendarRole;
import app.security.CurrentUser;
import app.user.AppUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.OffsetDateTime;
import java.util.List;

@Named
@RequestScoped
public class AppInvitationView {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    @Inject
    private AppInvitationService appInvitationService;

    @Inject
    private ApplicationUrlService applicationUrlService;

    private Long selectedCalendarId;
    private List<EditableCalendarOption> editableCalendars = List.of();
    private List<AppInvitationRow> invitations = List.of();
    private String generatedInviteLink;

    @PostConstruct
    public void load() {
        AppUser actor = currentUser.require();
        editableCalendars = calendarService.findCalendarsForUser(actor).stream()
                .filter(this::canCreateEditorInvitation)
                .map(calendar -> new EditableCalendarOption(calendar.getCalendarId(), calendar.getCalendarName()))
                .toList();
        reloadInvitations(actor);
    }

    public void createAppInvitation() {
        try {
            AppUser actor = currentUser.require();
            AppInvitation invitation = appInvitationService.createAppInvitation(actor);
            generatedInviteLink = invitationLink(invitation.getInviteToken());
            reloadInvitations(actor);
            addMessage(FacesMessage.SEVERITY_INFO, "App invitation created.", "Share the generated link directly.");
        } catch (AuthorizationException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Invitation failed.", exception.getMessage());
        }
    }

    public void createEditorInvitation() {
        try {
            if (selectedCalendarId == null) {
                throw new ValidationException("Calendar is required.");
            }
            AppUser actor = currentUser.require();
            AppInvitation invitation = appInvitationService.createCalendarEditorInvitation(actor, selectedCalendarId, null);
            generatedInviteLink = invitationLink(invitation.getInviteToken());
            reloadInvitations(actor);
            addMessage(FacesMessage.SEVERITY_INFO, "Editor invitation created.", "Share the generated link directly.");
        } catch (AuthorizationException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Invitation failed.", exception.getMessage());
        }
    }

    public void revokeInvitation(Long invitationId) {
        try {
            AppUser actor = currentUser.require();
            appInvitationService.revokeInvitation(actor, invitationId);
            reloadInvitations(actor);
            addMessage(FacesMessage.SEVERITY_INFO, "Invitation revoked.", "The link can no longer be used.");
        } catch (AuthorizationException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Revoke failed.", exception.getMessage());
        }
    }

    public Long getSelectedCalendarId() {
        return selectedCalendarId;
    }

    public void setSelectedCalendarId(Long selectedCalendarId) {
        this.selectedCalendarId = selectedCalendarId;
    }

    public List<EditableCalendarOption> getEditableCalendars() {
        return editableCalendars;
    }

    public boolean isHasEditableCalendars() {
        return !editableCalendars.isEmpty();
    }

    public List<AppInvitationRow> getInvitations() {
        return invitations;
    }

    public String getGeneratedInviteLink() {
        return generatedInviteLink;
    }

    public boolean isHasGeneratedInviteLink() {
        return generatedInviteLink != null && !generatedInviteLink.isBlank();
    }

    private boolean canCreateEditorInvitation(CalendarMembershipSummary calendar) {
        return calendar.getRole() == CalendarRole.EDITOR || calendar.getRole() == CalendarRole.ADMIN;
    }

    private void reloadInvitations(AppUser actor) {
        invitations = appInvitationService.listInvitations(actor).stream()
                .map(this::toRow)
                .toList();
    }

    private AppInvitationRow toRow(AppInvitation invitation) {
        return new AppInvitationRow(
                invitation.getId(),
                invitationLink(invitation.getInviteToken()),
                invitationScope(invitation),
                invitationStatus(invitation),
                invitation.getCreatedAt(),
                invitation.getAcceptedAt() == null && invitation.getRevokedAt() == null);
    }

    private String invitationScope(AppInvitation invitation) {
        if (invitation.getCalendar() == null) {
            return "App only";
        }
        return "Editor: " + invitation.getCalendar().getName();
    }

    private String invitationStatus(AppInvitation invitation) {
        if (invitation.getAcceptedAt() != null) {
            return "Used";
        }
        if (invitation.getRevokedAt() != null) {
            return "Revoked";
        }
        return "Available";
    }

    private String invitationLink(String inviteToken) {
        return applicationUrlService.linkTo("/register?token=" + inviteToken);
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public static final class EditableCalendarOption {
        private final Long id;
        private final String name;

        private EditableCalendarOption(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public static final class AppInvitationRow {
        private final Long id;
        private final String inviteLink;
        private final String scope;
        private final String status;
        private final OffsetDateTime createdAt;
        private final boolean revocable;

        private AppInvitationRow(
                Long id,
                String inviteLink,
                String scope,
                String status,
                OffsetDateTime createdAt,
                boolean revocable) {
            this.id = id;
            this.inviteLink = inviteLink;
            this.scope = scope;
            this.status = status;
            this.createdAt = createdAt;
            this.revocable = revocable;
        }

        public Long getId() {
            return id;
        }

        public String getInviteLink() {
            return inviteLink;
        }

        public String getScope() {
            return scope;
        }

        public String getStatus() {
            return status;
        }

        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }

        public boolean isRevocable() {
            return revocable;
        }
    }
}
