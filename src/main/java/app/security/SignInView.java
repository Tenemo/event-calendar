package app.security;

import app.invitation.InvitationToken;
import app.user.ApplicationUser;
import app.user.UserService;
import app.web.FacesMessages;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Named
@RequestScoped
public class SignInView {
    @Inject
    private SecurityContext securityContext;

    @Inject
    private UserService userService;

    private String username;
    private String password;
    private String invitationToken;
    private boolean passwordChanged;

    public void signIn() throws ServletException {
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
            ApplicationUser authenticatedUser = securityContext.getCallerPrincipal() == null
                    ? null
                    : userService.findByUsername(securityContext.getCallerPrincipal().getName())
                            .orElse(null);
            if (authenticatedUser == null) {
                AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
                response.setStatus(HttpServletResponse.SC_OK);
                addFailureMessage("Sign-in failed. Check your username and password.");
                return;
            }
            AuthenticatedSessionSecurity.establishAuthenticatedSession(
                    request, authenticatedUser.getPasswordVersion());
            RelativeRedirect.send(facesContext, successfulSignInRoute(invitationToken));
            return;
        }
        if (status == AuthenticationStatus.SEND_CONTINUE) {
            facesContext.responseComplete();
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        addFailureMessage("Sign-in failed. Check your username and password.");
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

    public String getPasswordChangedParameter() {
        return Boolean.toString(passwordChanged);
    }

    public void setPasswordChangedParameter(String value) {
        passwordChanged = "true".equals(value);
    }

    static String successfulSignInRoute(String invitationToken) {
        String normalizedToken = InvitationToken.normalize(invitationToken);
        return InvitationToken.isValidCandidate(normalizedToken)
                ? "/register?token=" + URLEncoder.encode(normalizedToken, StandardCharsets.UTF_8)
                : AuthenticatedApplicationFilter.DEFAULT_AUTHENTICATED_ROUTE;
    }

    private void addFailureMessage(String detail) {
        FacesMessages.add(FacesMessage.SEVERITY_ERROR, "Sign-in failed.", detail);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
