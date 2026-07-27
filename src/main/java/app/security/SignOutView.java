package app.security;

import app.web.RelativeRedirect;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

@Named
@RequestScoped
public class SignOutView {
    @Inject
    private AuthenticationAuditService authenticationAuditService;

    public void signOut() throws IOException, ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        authenticationAuditService.recordSignOut();
        RelativeRedirect.send(facesContext, "/");
    }
}
