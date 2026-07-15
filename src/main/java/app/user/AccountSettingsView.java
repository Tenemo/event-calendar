package app.user;

import app.security.AuthenticatedSessionSecurity;
import app.security.CurrentUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

@Named
@RequestScoped
public class AccountSettingsView {
    @Inject
    private CurrentUser currentUser;

    @Inject
    private UserService userService;

    private String currentPassword;
    private String newPassword;
    private String newPasswordConfirmation;

    public void changePassword() throws IOException, ServletException {
        try {
            userService.changePassword(
                    currentUser.require(),
                    currentPassword,
                    newPassword,
                    newPasswordConfirmation);
            signOutAndRedirect();
        } catch (AuthorizationException | ValidationException exception) {
            FacesContext.getCurrentInstance().addMessage(
                    null,
                    new FacesMessage(
                            FacesMessage.SEVERITY_ERROR,
                            "Password could not be changed.",
                            exception.getMessage()));
        }
    }

    public String getUsername() {
        return currentUser.require().getUsername();
    }

    private void signOutAndRedirect() throws IOException, ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        facesContext.getExternalContext().redirect(
                facesContext.getExternalContext().getRequestContextPath() + "/login?passwordChanged=true");
        facesContext.responseComplete();
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
