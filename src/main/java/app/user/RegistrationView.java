package app.user;

import app.invitation.Invitation;
import app.invitation.InvitationService;
import app.security.AuthenticatedSessionSecurity;
import app.security.CurrentUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Named
@RequestScoped
public class RegistrationView {
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
    private String invitationToken;

    public void register() throws IOException {
        try {
            ApplicationUser registeredUser = registrationService.register(
                    invitationToken,
                    username,
                    displayName,
                    password,
                    calendarName);
            authenticateAndRedirect(registeredUser);
        } catch (ValidationException exception) {
            FacesContext.getCurrentInstance().addMessage(
                    null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Registration failed.", exception.getMessage()));
        }
    }

    public void acceptInvitation() throws IOException {
        try {
            Invitation invitation = invitationService.acceptInvitation(invitationToken, currentUser.require());
            String route = invitation.getCalendar() == null
                    ? "/app/calendars"
                    : "/calendar/" + invitation.getCalendar().getPublicToken();
            FacesContext facesContext = FacesContext.getCurrentInstance();
            facesContext.getExternalContext().redirect(facesContext.getExternalContext().getRequestContextPath() + route);
            facesContext.responseComplete();
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
        return invitationToken;
    }

    public void setInvitationToken(String invitationToken) {
        this.invitationToken = invitationToken;
    }

    private void authenticateAndRedirect(ApplicationUser registeredUser) throws IOException {
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
            AuthenticatedSessionSecurity.establishAuthenticatedSession(request, registeredUser);
            facesContext.getExternalContext().redirect(facesContext.getExternalContext().getRequestContextPath() + "/app/calendars");
            facesContext.responseComplete();
        } else if (status == AuthenticationStatus.SEND_CONTINUE) {
            facesContext.responseComplete();
        } else {
            facesContext.getExternalContext().getFlash().setKeepMessages(true);
            facesContext.addMessage(
                    null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Registration succeeded.", "Sign in with the new account."));
            facesContext.getExternalContext().redirect(facesContext.getExternalContext().getRequestContextPath() + "/login");
            facesContext.responseComplete();
        }
    }
}
