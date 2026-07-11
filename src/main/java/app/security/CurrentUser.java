package app.security;

import app.user.ApplicationUser;
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

    private Optional<ApplicationUser> currentUser = Optional.empty();
    private boolean currentUserLoaded;

    public Optional<ApplicationUser> find() {
        if (!currentUserLoaded) {
            currentUser = loadCurrentUser();
            currentUserLoaded = true;
        }
        return currentUser;
    }

    public ApplicationUser require() {
        return find().orElseThrow(() -> new AuthorizationException("Sign-in is required."));
    }

    public boolean isSignedIn() {
        return find().isPresent();
    }

    private Optional<ApplicationUser> loadCurrentUser() {
        Principal callerPrincipal = securityContext.getCallerPrincipal();
        if (callerPrincipal == null) {
            return Optional.empty();
        }
        return userService.findActiveByUsername(callerPrincipal.getName());
    }
}
