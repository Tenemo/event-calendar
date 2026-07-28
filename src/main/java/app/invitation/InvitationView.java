package app.invitation;

import app.calendar.CalendarService;
import app.config.ApplicationUrlService;
import app.security.CurrentUser;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import app.web.FacesMessages;
import app.web.ViewParameterParser;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Named
@ViewScoped
public class InvitationView implements Serializable {
    private static final DateTimeFormatter INVITATION_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("MMM d, yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    @Inject
    private CurrentUser currentUser;

    @Inject
    private CalendarService calendarService;

    @Inject
    private InvitationService invitationService;

    @Inject
    private ApplicationUrlService applicationUrlService;

    private Long selectedCalendarId;
    private List<EditableCalendarOption> editableCalendars = List.of();
    private List<InvitationItem> invitations = List.of();
    private String generatedInvitationLink;

    @PostConstruct
    public void load() {
        ApplicationUser actingUser = currentUser.require();
        editableCalendars = calendarService.findCalendarsForUser(actingUser).stream()
                .map(calendar -> new EditableCalendarOption(
                        calendar.getCalendarId(), calendar.getCalendarName()))
                .toList();
        refreshInvitations(actingUser);
    }

    public void createRegistrationInvitation() {
        createInvitation(null);
    }

    public void createEditorInvitation() {
        if (selectedCalendarId == null) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Invitation failed.",
                    "Calendar is required.");
            return;
        }
        createInvitation(selectedCalendarId);
    }

    private void createInvitation(Long calendarId) {
        try {
            ApplicationUser actingUser = currentUser.require();
            Invitation invitation = calendarId == null
                    ? invitationService.createRegistrationInvitation(actingUser)
                    : invitationService.createCalendarEditorInvitation(actingUser, calendarId);
            generatedInvitationLink = invitationLink(invitation.getInvitationToken());
            refreshInvitations(actingUser);
            FacesMessages.add(
                    FacesMessage.SEVERITY_INFO,
                    "Invitation created.",
                    "Share the generated link directly.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
            FacesMessages.add(FacesMessage.SEVERITY_ERROR, "Invitation failed.", exception.getMessage());
        }
    }

    public void revokeInvitation() {
        try {
            ApplicationUser actingUser = currentUser.require();
            Long invitationId = parseInvitationId(FacesContext.getCurrentInstance()
                    .getExternalContext()
                    .getRequestParameterMap()
                    .get("invitationId"));
            invitationService.revokeInvitation(actingUser, invitationId);
            refreshInvitations(actingUser);
            FacesMessages.add(
                    FacesMessage.SEVERITY_INFO,
                    "Invitation revoked.",
                    "The link can no longer be used.");
        } catch (AuthorizationException | NotFoundException | ValidationException exception) {
            FacesMessages.add(FacesMessage.SEVERITY_ERROR, "Revoke failed.", exception.getMessage());
        }
    }

    private static Long parseInvitationId(String submittedInvitationId) {
        return ViewParameterParser.positiveLong(submittedInvitationId)
                .orElseThrow(() -> new ValidationException("Invitation is invalid."));
    }

    private void refreshInvitations(ApplicationUser actingUser) {
        invitations = invitationService.listOutstandingInvitations(actingUser).stream()
                .map(invitation -> new InvitationItem(
                        invitation.id(),
                        invitationLink(invitation.invitationToken()),
                        invitation.calendarName() == null
                                ? "Registration invitation"
                                : "Editor: " + invitation.calendarName(),
                        invitation.createdAt(),
                        invitation.expiresAt()))
                .toList();
    }

    private String invitationLink(String invitationToken) {
        return applicationUrlService.linkTo("/register?token=" + invitationToken);
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

    public List<InvitationItem> getInvitations() {
        return invitations;
    }

    public String getGeneratedInvitationLink() {
        return generatedInvitationLink;
    }

    public boolean hasGeneratedInvitationLink() {
        return generatedInvitationLink != null && !generatedInvitationLink.isBlank();
    }

    public static final class EditableCalendarOption implements Serializable {
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

    public static final class InvitationItem implements Serializable {
        private final Long id;
        private final String invitationLink;
        private final String scope;
        private final String createdAtLabel;
        private final String expiresAtLabel;

        private InvitationItem(
                Long id,
                String invitationLink,
                String scope,
                OffsetDateTime createdAt,
                OffsetDateTime expiresAt) {
            this.id = id;
            this.invitationLink = invitationLink;
            this.scope = scope;
            this.createdAtLabel = formatTimestamp(createdAt);
            this.expiresAtLabel = formatTimestamp(expiresAt);
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

        public String getCreatedAtLabel() {
            return createdAtLabel;
        }

        public String getExpiresAtLabel() {
            return expiresAtLabel;
        }
    }

    static String formatTimestamp(OffsetDateTime timestamp) {
        return INVITATION_TIMESTAMP_FORMAT.format(timestamp);
    }
}
