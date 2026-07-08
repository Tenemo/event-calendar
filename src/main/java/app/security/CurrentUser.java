package app.security;

import app.user.AppUser;
import app.user.UserService;
import app.util.AuthorizationException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.SecurityContext;
import java.security.Principal;
import java.util.Optional;

@Named
@RequestScoped
public class CurrentUser {
    @Inject
    private SecurityContext securityContext;

    @Inject
    private UserService userService;

    public Optional<AppUser> find() {
        Principal callerPrincipal = securityContext.getCallerPrincipal();
        if (callerPrincipal == null) {
            return Optional.empty();
        }
        return userService.findActiveByUsername(callerPrincipal.getName());
    }

    public AppUser require() {
        return find().orElseThrow(() -> new AuthorizationException("Sign-in is required."));
    }

    public boolean isSignedIn() {
        return securityContext.getCallerPrincipal() != null;
    }
}
