package app.security;

import app.invitation.InvitationToken;
import app.web.RelativeRedirect;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

@Named
@RequestScoped
public class LoginView {
    @Inject
    private SecurityContext securityContext;

    @Inject
    private PasswordValidationState passwordValidationState;

    private String username;
    private String password;
    private String invitationToken;
    private boolean passwordChanged;
    private boolean reauthenticationRequired;

    public void login() throws IOException, ServletException {
        if (isBlank(username) || isBlank(password)) {
            addFailureMessage("Username and password are required.");
            return;
        }

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
                response.setStatus(HttpServletResponse.SC_OK);
                addFailureMessage("Sign-in failed. Check your username and password.");
                return;
            }
            AuthenticatedSessionSecurity.establishAuthenticatedSession(
                    request,
                    validatedPasswordVersion.getAsLong());
            RelativeRedirect.send(facesContext, successfulLoginRoute(invitationToken));
        } else if (status == AuthenticationStatus.SEND_CONTINUE) {
            facesContext.responseComplete();
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            addFailureMessage("Sign-in failed. Check your username and password.");
        }
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getInvitationToken() {
        return invitationToken;
    }

    public void setInvitationToken(String invitationToken) {
        this.invitationToken = invitationToken;
    }

    public boolean isPasswordChanged() {
        return passwordChanged;
    }

    public void setPasswordChanged(boolean passwordChanged) {
        this.passwordChanged = passwordChanged;
    }

    public boolean isReauthenticationRequired() {
        return reauthenticationRequired;
    }

    public void setReauthenticationRequired(boolean reauthenticationRequired) {
        this.reauthenticationRequired = reauthenticationRequired;
    }

    static String successfulLoginRoute(String invitationToken) {
        String normalizedInvitationToken = InvitationToken.normalize(invitationToken);
        return InvitationToken.isValidCandidate(normalizedInvitationToken)
                ? "/register?token="
                        + URLEncoder.encode(normalizedInvitationToken, StandardCharsets.UTF_8)
                : AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;
    }

    private void addFailureMessage(String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sign-in failed.", detail));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
