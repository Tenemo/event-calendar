package app.user;

import app.security.AuthenticatedSessionSecurity;
import app.security.CurrentUser;
import app.security.LocalDevelopmentAutoSignIn;
import app.util.AuthorizationException;
import app.util.ValidationException;
import app.web.FacesMessages;
import app.web.RelativeRedirect;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

@Named
@RequestScoped
public class AccountSettingsView {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private UserService userService;

    @Inject
    private LocalDevelopmentAutoSignIn localDevelopmentAutoSignIn;

    private String currentPassword;
    private String newPassword;
    private String newPasswordConfirmation;

    public void changePassword() throws ServletException {
        try {
            userService.changePassword(
                    currentUser.require(),
                    currentPassword,
                    newPassword,
                    newPasswordConfirmation);
            signOutAndRedirect();
        } catch (AuthorizationException | ValidationException exception) {
            FacesMessages.add(
                    FacesMessage.SEVERITY_ERROR,
                    "Password could not be changed.",
                    exception.getMessage());
        }
    }

    public String getUsername() {
        return currentUser.require().getUsername();
    }

    private void signOutAndRedirect() throws ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        localDevelopmentAutoSignIn.suppressAfterExplicitSignOut(request);
        RelativeRedirect.send(facesContext, "/sign-in?passwordChanged=true");
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getNewPasswordConfirmation() {
        return newPasswordConfirmation;
    }

    public void setNewPasswordConfirmation(String newPasswordConfirmation) {
        this.newPasswordConfirmation = newPasswordConfirmation;
    }
}
