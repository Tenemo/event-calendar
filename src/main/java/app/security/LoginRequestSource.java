package app.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

@RequestScoped
public class LoginRequestSource {
    @Inject
    private HttpServletRequest request;

    public String getSourceIdentifier() {
        return request.getRemoteAddr();
    }
}
