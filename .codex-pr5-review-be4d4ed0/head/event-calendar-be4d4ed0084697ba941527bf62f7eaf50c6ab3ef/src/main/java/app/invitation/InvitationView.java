package app.invitation;

import app.calendar.CalendarService;
import app.config.ApplicationUrlService;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.LazyDataModel;
import org.primefaces.model.SortMeta;

@Named
@RequestScoped
public class InvitationView {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    @Inject
    private InvitationService invitationService;

    @Inject
    private ApplicationUrlService applicationUrlService;

    @Inject
    private InvitationPolicy invitationPolicy;

    private Long selectedCalendarId;
    private List<EditableCalendarOption> editableCalendars = List.of();
    private LazyDataModel<InvitationRow> invitations;
    private String generatedInvitationLink;

    @PostConstruct
    public void load() {
        ApplicationUser actingUser = currentUser.require();
        editableCalendars = calendarService.findCalendarsForUser(actingUser).stream()
                .map(calendar -> new EditableCalendarOption(calendar.getCalendarId(), calendar.getCalendarName()))
                .toList();
        invitations = new InvitationLazyDataModel(actingUser);
    }

    public void createRegistrationInvitation() {
        try {
            ApplicationUser actingUser = currentUser.require();
            Invitation invitation = invitationService.createRegistrationInvitation(actingUser);
            generatedInvitationLink = invitationLink(invitation.getInvitationToken());
            addMessage(FacesMessage.SEVERITY_INFO, "Registration invitation created.", "Share the generated link directly.");
        } catch (AuthorizationException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Invitation failed.", exception.getMessage());
        }
    }

    public void createEditorInvitation() {
        try {
            if (selectedCalendarId == null) {
                throw new ValidationException("Calendar is required.");
            }
            ApplicationUser actingUser = currentUser.require();
            Invitation invitation = invitationService.createCalendarEditorInvitation(actingUser, selectedCalendarId);
            generatedInvitationLink = invitationLink(invitation.getInvitationToken());
            addMessage(FacesMessage.SEVERITY_INFO, "Editor invitation created.", "Share the generated link directly.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Invitation failed.", exception.getMessage());
        }
    }

    public void revokeInvitation(Long invitationId) {
        try {
            ApplicationUser actingUser = currentUser.require();
            invitationService.revokeInvitation(actingUser, invitationId);
            addMessage(FacesMessage.SEVERITY_INFO, "Invitation revoked.", "The link can no longer be used.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
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

    public boolean hasEditableCalendars() {
        return !editableCalendars.isEmpty();
    }

    public LazyDataModel<InvitationRow> getInvitations() {
        return invitations;
    }

    public String getGeneratedInvitationLink() {
        return generatedInvitationLink;
    }

    public boolean hasGeneratedInvitationLink() {
        return generatedInvitationLink != null && !generatedInvitationLink.isBlank();
    }

    private InvitationRow toRow(Invitation invitation, OffsetDateTime currentTime) {
        InvitationStatus status = invitationPolicy.status(
                invitation.getRevokedAt(),
                invitation.getAcceptedAt(),
                invitation.getExpiresAt(),
                currentTime);
        return new InvitationRow(
                invitation.getId(),
                invitationLink(invitation.getInvitationToken()),
                invitationScope(invitation),
                invitationStatus(status),
                invitation.getCreatedAt(),
                status.isRevocable());
    }

    private String invitationScope(Invitation invitation) {
        if (invitation.getCalendar() == null) {
            return "Registration invitation";
        }
        return "Editor: " + invitation.getCalendar().getName();
    }

    private String invitationStatus(InvitationStatus status) {
        return switch (status) {
            case AVAILABLE -> "Available";
            case ACCEPTED -> "Accepted";
            case REVOKED -> "Revoked";
            case EXPIRED -> "Expired";
        };
    }

    private String invitationLink(String invitationToken) {
        return applicationUrlService.linkTo("/register?token=" + invitationToken);
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    private final class InvitationLazyDataModel extends LazyDataModel<InvitationRow> {
        private final ApplicationUser actingUser;

        private InvitationLazyDataModel(ApplicationUser actingUser) {
            this.actingUser = actingUser;
        }

        @Override
        public int count(Map<String, FilterMeta> filterMetadata) {
            return Math.toIntExact(invitationService.countInvitations(actingUser));
        }

        @Override
        public List<InvitationRow> load(
                int firstResult,
                int pageSize,
                Map<String, SortMeta> sortMetadata,
                Map<String, FilterMeta> filterMetadata) {
            OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
            return invitationService.listInvitations(actingUser, firstResult, pageSize).stream()
                    .map(invitation -> toRow(invitation, currentTime))
                    .toList();
        }
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

    public static final class InvitationRow {
        private final Long id;
        private final String invitationLink;
        private final String scope;
        private final String status;
        private final OffsetDateTime createdAt;
        private final boolean revocable;

        private InvitationRow(
                Long id,
                String invitationLink,
                String scope,
                String status,
                OffsetDateTime createdAt,
                boolean revocable) {
            this.id = id;
            this.invitationLink = invitationLink;
            this.scope = scope;
            this.status = status;
            this.createdAt = createdAt;
            this.revocable = revocable;
        }

        public Long getId() {
            return id;
        }

        public String getInvitationLink() {
            return invitationLink;
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
