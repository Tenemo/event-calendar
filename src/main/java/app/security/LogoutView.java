package app.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Named
@RequestScoped
public class LogoutView {
    public void logout() throws IOException, ServletException {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        HttpServletRequest request = (HttpServletRequest) facesContext.getExternalContext().getRequest();
        HttpServletResponse response = (HttpServletResponse) facesContext.getExternalContext().getResponse();

        AuthenticatedSessionSecurity.invalidateSessionAndLogout(request);
        response.sendRedirect(facesContext.getExternalContext().getRequestContextPath() + "/");
        facesContext.responseComplete();
    }
}
