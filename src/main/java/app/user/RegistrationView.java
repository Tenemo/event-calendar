package app.user;

import app.invitation.Invitation;
import app.invitation.InvitationPreview;
import app.invitation.InvitationService;
import app.invitation.InvitationToken;
import app.security.AuthenticatedSessionSecurity;
import app.security.CurrentUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import app.web.FacesMessages;
import app.web.RelativeRedirect;
import app.web.ViewParameterParser;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Named
@RequestScoped
public class RegistrationView {
    private static final DateTimeFormatter INVITATION_EXPIRATION_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH);

    @Inject
    private RegistrationService registrationService;

    @Inject
    private SecurityContext securityContext;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private InvitationService invitationService;

    private String username;
    private String displayName;
    private String calendarName;
    private String password;
    private String passwordConfirmation;
    private String invitationToken;
    private boolean invitationTokenRequestParametersInspected;
    private InvitationPreview invitationPreview;

    public void register() throws ServletException {
        try {
            ApplicationUser registeredUser = registrationService.register(
                    invitationToken(),
                    username,
                    displayName,
                    password,
                    passwordConfirmation,
                    calendarName);
            authenticateAndRedirect(registeredUser.getPasswordVersion());
        } catch (ValidationException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Registration failed.",
                    exception.getMessage());
        }
    }

    public void acceptInvitation() {
        try {
            Invitation invitation = invitationService.acceptInvitation(invitationToken(), currentUser.require());
            String route = invitation.getCalendar() == null
                    ? "/app/calendars"
                    : "/" + invitation.getCalendar().getCalendarLinkToken();
            FacesContext facesContext = FacesContext.getCurrentInstance();
            RelativeRedirect.send(facesContext, route);
        } catch (AuthorizationException | ValidationException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Invitation could not be accepted.",
                    exception.getMessage());
        }
    }

    public boolean isSignedIn() {
        return currentUser.isSignedIn();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCalendarName() {
        return calendarName;
    }

    public void setCalendarName(String calendarName) {
        this.calendarName = calendarName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getInvitationToken() {
        return invitationToken();
    }

    public void setInvitationToken(String invitationToken) {
        String normalizedInvitationToken = InvitationToken.normalize(invitationToken);
        this.invitationToken = InvitationToken.isValidCandidate(normalizedInvitationToken)
                ? normalizedInvitationToken
                : "";
        invitationPreview = null;
    }

    public String getPasswordConfirmation() {
        return passwordConfirmation;
    }

    public void setPasswordConfirmation(String passwordConfirmation) {
        this.passwordConfirmation = passwordConfirmation;
    }

    public boolean isInvitationAvailable() {
        return invitationPreview().isAvailable();
    }

    public boolean isRegistrationInvitation() {
        return invitationPreview().isRegistrationInvitation();
    }

    public boolean isCalendarEditorInvitation() {
        return invitationPreview().isCalendarEditorInvitation();
    }

    public String getInvitationCalendarName() {
        return invitationPreview().getCalendarName();
    }

    public boolean hasInvitationExpiration() {
        return invitationPreview().getExpiresAt() != null;
    }

    public String getInvitationExpiration() {
        OffsetDateTime expiration = invitationPreview().getExpiresAt();
        return expiration == null
                ? null
                : INVITATION_EXPIRATION_FORMAT.format(expiration);
    }

    private InvitationPreview invitationPreview() {
        if (invitationPreview == null) {
            invitationPreview = invitationService.previewInvitation(invitationToken());
        }
        return invitationPreview;
    }

    private String invitationToken() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!invitationTokenRequestParametersInspected && facesContext != null) {
            invitationToken = ViewParameterParser.invitationToken(
                            facesContext.getExternalContext().getRequestParameterValuesMap(),
                            facesContext.isPostback())
                    .orElse("");
            invitationTokenRequestParametersInspected = true;
            invitationPreview = null;
        } else if (invitationToken == null) {
            invitationToken = "";
        }
        return invitationToken;
    }

    private void authenticateAndRedirect(long passwordVersion) throws ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        HttpServletResponse response = (HttpServletResponse) facesContext.getExternalContext().getResponse();

        AuthenticationStatus status = securityContext.authenticate(
                request,
                response,
                AuthenticationParameters.withParams()
                        .credential(new UsernamePasswordCredential(username, password))
                        .newAuthentication(true));

        if (status == AuthenticationStatus.SUCCESS) {
            AuthenticatedSessionSecurity.establishAuthenticatedSession(request, passwordVersion);
            RelativeRedirect.send(facesContext, "/app/calendars");
        } else if (status == AuthenticationStatus.SEND_CONTINUE) {
            facesContext.responseComplete();
        } else {
            redirectToLoginAfterRegistration(facesContext);
        }
    }

    private void redirectToLoginAfterRegistration(FacesContext facesContext) {
        FacesMessages.add(
                FacesMessage.SEVERITY_INFO,
                "Registration succeeded.",
                "Sign in with the new account.");
        RelativeRedirect.sendKeepingMessages(facesContext, "/sign-in");
    }
}
