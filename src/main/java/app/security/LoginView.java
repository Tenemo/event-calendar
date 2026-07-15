package app.security;

import app.user.ApplicationUser;
import app.user.UserService;
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

@Named
@RequestScoped
public class LoginView {
    @Inject
    private SecurityContext securityContext;

    @Inject
    private UserService userService;

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
            ApplicationUser authenticatedUser = userService.findActiveByUsername(username).orElse(null);
            if (authenticatedUser == null) {
                AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
                response.setStatus(HttpServletResponse.SC_OK);
                addFailureMessage("Sign-in failed. Check your username and password.");
                return;
            }
            AuthenticatedSessionSecurity.establishAuthenticatedSession(request, authenticatedUser);
            String route = isBlank(invitationToken)
                    ? "/app/calendars"
                    : "/register?token=" + URLEncoder.encode(invitationToken.trim(), StandardCharsets.UTF_8);
            redirectToApplication(facesContext, route);
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

    private void redirectToApplication(FacesContext facesContext, String route) throws IOException {
        facesContext.getExternalContext().redirect(facesContext.getExternalContext().getRequestContextPath() + route);
        facesContext.responseComplete();
    }

    private void addFailureMessage(String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sign-in failed.", detail));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
