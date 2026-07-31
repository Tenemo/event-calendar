package app.security;

import app.web.RelativeRedirect;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

@Named
@RequestScoped
public class SignOutView {
    @Inject
    private LocalDevelopmentAutoSignIn localDevelopmentAutoSignIn;

    public void signOut() throws ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        localDevelopmentAutoSignIn.suppressAfterExplicitSignOut(request);
        RelativeRedirect.send(facesContext, "/");
    }
}
