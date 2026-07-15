package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.user.ApplicationUser;
import app.user.UserService;
import app.util.AuthorizationException;
import jakarta.security.enterprise.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CurrentUserTest {
    @Test
    void signedInRequiresAnActiveApplicationUser() {
        ApplicationUser activeUser = activeUser("piotr");
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

    @Test
    void passwordVersionMismatchRejectsAnOtherwiseActiveAuthenticatedSession() {
        ApplicationUser activeUser = activeUser("piotr");
        activeUser.setPasswordVersion(4);
        RecordingUserService userService = new RecordingUserService(Optional.of(activeUser));
        CurrentUser currentUser = currentUser("piotr", userService, 3);

        assertAll(
                () -> assertFalse(currentUser.isSignedIn()),
                () -> assertThrows(AuthorizationException.class, currentUser::require),
                () -> assertEquals(1, userService.lookupCount));
    }

    private static CurrentUser currentUser(String principalName, UserService userService) {
        return currentUser(principalName, userService, 0);
    }

    private static CurrentUser currentUser(
            String principalName,
            UserService userService,
            long sessionPasswordVersion) {
        CurrentUser currentUser = new CurrentUser();
        setField(currentUser, "securityContext", securityContext(principalName));
        setField(currentUser, "userService", userService);
        setField(currentUser, "request", request(sessionPasswordVersion));
        return currentUser;
    }

    private static HttpServletRequest request(long sessionPasswordVersion) {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                HttpSession.class.getClassLoader(),
                new Class<?>[] {HttpSession.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getAttribute")
                            && AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE.equals(arguments[0])) {
                        return sessionPasswordVersion;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> method.getName().equals("getSession")
                        ? session
                        : defaultValue(method.getReturnType()));
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

    private static ApplicationUser activeUser(String username) {
        ApplicationUser user = new ApplicationUser();
        user.setUsername(username);
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RecordingUserService extends UserService {
        private final Optional<ApplicationUser> user;
        private int lookupCount;
        private String lastUsername;

        private RecordingUserService(Optional<ApplicationUser> user) {
            this.user = user;
        }

        @Override
        public Optional<ApplicationUser> findActiveByUsername(String username) {
            lookupCount++;
            lastUsername = username;
            return user;
        }
    }
}
