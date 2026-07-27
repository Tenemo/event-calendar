package app.user;

import app.invitation.Invitation;
import app.invitation.InvitationAdmissionPreview;
import app.invitation.InvitationService;
import app.invitation.InvitationToken;
import app.security.AuthenticatedSessionSecurity;
import app.security.AuthenticationAuditService;
import app.security.CurrentUser;
import app.security.PasswordValidationState;
import app.util.AuthorizationException;
import app.util.ValidationException;
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
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;

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

    @Inject
    private PasswordValidationState passwordValidationState;

    @Inject
    private AuthenticationAuditService authenticationAuditService;

    private String username;
    private String displayName;
    private String calendarName;
    private String password;
    private String passwordConfirmation;
    private String invitationToken;
    private boolean invitationTokenRequestParametersInspected;
    private InvitationAdmissionPreview invitationPreview;

    public void register() throws IOException, ServletException {
        try {
            registrationService.register(
                    invitationToken(),
                    username,
                    displayName,
                    password,
                    passwordConfirmation,
                    calendarName);
            authenticateAndRedirect();
        } catch (ValidationException exception) {
            FacesContext.getCurrentInstance().addMessage(
                    null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Registration failed.", exception.getMessage()));
        }
    }

    public void acceptInvitation() throws IOException {
        try {
            Invitation invitation = invitationService.acceptInvitation(invitationToken(), currentUser.require());
            String route = invitation.getCalendar() == null
                    ? "/app/calendars"
                    : "/" + invitation.getCalendar().getCalendarLinkToken();
            FacesContext facesContext = FacesContext.getCurrentInstance();
            RelativeRedirect.send(facesContext, route);
        } catch (AuthorizationException | ValidationException exception) {
            FacesContext.getCurrentInstance().addMessage(
                    null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Invitation could not be accepted.", exception.getMessage()));
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

    private InvitationAdmissionPreview invitationPreview() {
        if (invitationPreview == null) {
            invitationPreview = invitationService.previewAdmission(invitationToken());
        }
        return invitationPreview;
    }

    private String invitationToken() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!invitationTokenRequestParametersInspected && facesContext != null) {
            invitationToken = ViewParameterParser.invitationToken(
                            facesContext.getExternalContext().getRequestParameterValuesMap(),
                            false,
                            facesContext.isPostback())
                    .orElse("");
            invitationTokenRequestParametersInspected = true;
            invitationPreview = null;
        } else if (invitationToken == null) {
            invitationToken = "";
        }
        return invitationToken;
    }

    private void authenticateAndRedirect() throws IOException, ServletException {
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
            OptionalLong validatedPasswordVersion = passwordValidationState.consumeValidatedPasswordVersion(
                    securityContext.getCallerPrincipal());
            if (validatedPasswordVersion.isEmpty()) {
                AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
                authenticationAuditService.recordForcedSessionInvalidation();
                redirectToLoginAfterRegistration(facesContext);
                return;
            }
            AuthenticatedSessionSecurity.establishAuthenticatedSession(
                    request,
                    validatedPasswordVersion.getAsLong());
            RelativeRedirect.send(facesContext, "/app/calendars");
        } else if (status == AuthenticationStatus.SEND_CONTINUE) {
            facesContext.responseComplete();
        } else {
            redirectToLoginAfterRegistration(facesContext);
        }
    }

    private void redirectToLoginAfterRegistration(FacesContext facesContext) throws IOException {
        facesContext.addMessage(
                null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Registration succeeded.", "Sign in with the new account."));
        RelativeRedirect.sendKeepingMessages(facesContext, "/login");
    }
}
