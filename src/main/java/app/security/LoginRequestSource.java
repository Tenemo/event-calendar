package app.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

@RequestScoped
public class LoginRequestSource {
    @Inject
    private HttpServletRequest request;

    @Inject
    private ClientRequestSourceResolver clientRequestSourceResolver;

    public String getSourceIdentifier() {
        return clientRequestSourceResolver.resolve(request);
    }
}
