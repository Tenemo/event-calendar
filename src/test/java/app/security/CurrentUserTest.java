package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.user.AppUser;
import app.user.UserService;
import app.util.AuthorizationException;
import jakarta.security.enterprise.SecurityContext;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CurrentUserTest {
    @Test
    void signedInRequiresAnActiveApplicationUser() {
        AppUser activeUser = activeUser("piotr");
        RecordingUserService userService = new RecordingUserService(Optional.of(activeUser));
        CurrentUser currentUser = currentUser("piotr", userService);

        assertAll(
                () -> assertTrue(currentUser.isSignedIn()),
                () -> assertSame(activeUser, currentUser.require()),
                () -> assertEquals(1, userService.lookupCount),
                () -> assertEquals("piotr", userService.lastUsername));
    }

    @Test
    void principalWithoutActiveApplicationUserIsNotSignedIn() {
        RecordingUserService userService = new RecordingUserService(Optional.empty());
        CurrentUser currentUser = currentUser("disabled", userService);

        assertAll(
                () -> assertFalse(currentUser.isSignedIn()),
                () -> assertThrows(AuthorizationException.class, currentUser::require),
                () -> assertEquals(1, userService.lookupCount),
                () -> assertEquals("disabled", userService.lastUsername));
    }

    @Test
    void missingPrincipalIsNotSignedInAndDoesNotQueryUsers() {
        RecordingUserService userService = new RecordingUserService(Optional.of(activeUser("piotr")));
        CurrentUser currentUser = currentUser(null, userService);

        assertAll(
                () -> assertFalse(currentUser.isSignedIn()),
                () -> assertThrows(AuthorizationException.class, currentUser::require),
                () -> assertEquals(0, userService.lookupCount));
    }

    private static CurrentUser currentUser(String principalName, UserService userService) {
        CurrentUser currentUser = new CurrentUser();
        setField(currentUser, "securityContext", securityContext(principalName));
        setField(currentUser, "userService", userService);
        return currentUser;
    }

    private static SecurityContext securityContext(String principalName) {
        return (SecurityContext) Proxy.newProxyInstance(
                SecurityContext.class.getClassLoader(),
                new Class<?>[] { SecurityContext.class },
                (proxy, method, arguments) -> {
                    String methodName = method.getName();
                    if (methodName.equals("getCallerPrincipal")) {
                        return principalName == null ? null : (Principal) () -> principalName;
                    }
                    if (methodName.equals("toString")) {
                        return "SecurityContext test proxy";
                    }
                    if (method.getReturnType() == Boolean.TYPE) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static AppUser activeUser(String username) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }

    private static final class RecordingUserService extends UserService {
        private final Optional<AppUser> user;
        private int lookupCount;
        private String lastUsername;

        private RecordingUserService(Optional<AppUser> user) {
            this.user = user;
        }

        @Override
        public Optional<AppUser> findActiveByUsername(String username) {
            lookupCount++;
            lastUsername = username;
            return user;
        }
    }
}
