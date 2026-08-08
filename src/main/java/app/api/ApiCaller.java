package app.api;

import app.user.ApplicationUser;
import app.util.AuthorizationException;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class ApiCaller {
    private ApplicationUser user;

    void authenticate(ApplicationUser authenticatedUser) {
        user = authenticatedUser;
    }

    public ApplicationUser require() {
        if (user == null) {
            throw new AuthorizationException("API authentication is required.");
        }
        return user;
    }
}
