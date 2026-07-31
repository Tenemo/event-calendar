package app.security;

import static app.testsupport.ProxyReturnValues.createInterfaceProxy;
import static app.testsupport.ProxyReturnValues.defaultValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.user.ApplicationUser;
import app.user.UserService;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LocalDevelopmentAutoSignInFilterTest {
    @Test
    void authenticatesTheSeededAdminAndEstablishesThePasswordVersionSession()
            throws Exception {
        ApplicationUser admin = admin(7);
        SecurityHarness securityHarness =
                new SecurityHarness(null, "admin", AuthenticationStatus.SUCCESS);
        RequestHarness requestHarness =
                new RequestHarness("/app/calendars", null, securityHarness);
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter(securityHarness, admin).doFilter(
                requestHarness.request,
                response().proxy,
                countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(1, securityHarness.authenticationCalls.get()),
                () -> assertInstanceOf(
                        LocalDevelopmentAutoSignInCredential.class,
                        securityHarness.authenticationParameters.get().getCredential()),
                () -> assertEquals(
                        true,
                        securityHarness.authenticationParameters.get().isNewAuthentication()),
                () -> assertEquals(1, filterChainCalls.get()),
                () -> assertEquals(
                        7L,
                        requestHarness.currentSession
                                .get()
                                .attributes
                                .get(AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE)),
                () -> assertEquals(
                        AuthenticatedSessionSecurity.AUTHENTICATED_SESSION_LIFETIME_SECONDS,
                        requestHarness.currentSession.get().maximumInactiveInterval.get()));
    }

    @Test
    void successfulSignInPageRequestRedirectsStraightToCalendars() throws Exception {
        ApplicationUser admin = admin(2);
        SecurityHarness securityHarness =
                new SecurityHarness(null, "admin", AuthenticationStatus.SUCCESS);
        RequestHarness requestHarness =
                new RequestHarness("/sign-in", null, securityHarness);
        ResponseHarness responseHarness = response();
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter(securityHarness, admin).doFilter(
                requestHarness.request,
                responseHarness.proxy,
                countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(0, filterChainCalls.get()),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseHarness.status.get()),
                () -> assertEquals(
                        "/app/calendars", responseHarness.headers.get("Location")),
                () -> assertEquals("no-store", responseHarness.headers.get("Cache-Control")));
    }

    @Test
    void preservesAnExistingManuallyAuthenticatedCurrentSession() throws Exception {
        ApplicationUser admin = admin(11);
        SecurityHarness securityHarness =
                new SecurityHarness("admin", "admin", AuthenticationStatus.SUCCESS);
        RequestHarness requestHarness =
                new RequestHarness("/app/calendars", 11L, securityHarness);
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter(securityHarness, admin).doFilter(
                requestHarness.request,
                response().proxy,
                countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(0, securityHarness.authenticationCalls.get()),
                () -> assertEquals(0, requestHarness.logoutCalls.get()),
                () -> assertEquals(0, requestHarness.sessionInvalidations.get()),
                () -> assertEquals(1, filterChainCalls.get()));
    }

    @Test
    void replacesAContainerPrincipalWhosePasswordVersionSessionIsStale()
            throws Exception {
        ApplicationUser admin = admin(5);
        SecurityHarness securityHarness =
                new SecurityHarness("admin", "admin", AuthenticationStatus.SUCCESS);
        RequestHarness requestHarness =
                new RequestHarness("/app/calendars", 4L, securityHarness);
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter(securityHarness, admin).doFilter(
                requestHarness.request,
                response().proxy,
                countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(1, requestHarness.logoutCalls.get()),
                () -> assertEquals(1, requestHarness.sessionInvalidations.get()),
                () -> assertEquals(1, securityHarness.authenticationCalls.get()),
                () -> assertEquals(1, filterChainCalls.get()),
                () -> assertEquals(
                        5L,
                        requestHarness.currentSession
                                .get()
                                .attributes
                                .get(AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE)));
    }

    @Test
    void containerAuthenticationFailureDoesNotReachTheRequestedPage() throws Exception {
        ApplicationUser admin = admin(0);
        SecurityHarness securityHarness =
                new SecurityHarness(null, null, AuthenticationStatus.SEND_FAILURE);
        RequestHarness requestHarness =
                new RequestHarness("/app/calendars", null, securityHarness);
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter(securityHarness, admin).doFilter(
                requestHarness.request,
                response().proxy,
                countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(1, securityHarness.authenticationCalls.get()),
                () -> assertEquals(0, filterChainCalls.get()),
                () -> assertNull(securityHarness.principal.get()));
    }

    @Test
    void nonHttpTrafficContinuesWithoutConsultingAuthentication() throws Exception {
        SecurityHarness securityHarness =
                new SecurityHarness(null, "admin", AuthenticationStatus.SUCCESS);
        AtomicInteger filterChainCalls = new AtomicInteger();

        filter(securityHarness, admin(0)).doFilter(
                interfaceProxy(ServletRequest.class),
                interfaceProxy(ServletResponse.class),
                countingFilterChain(filterChainCalls));

        assertAll(
                () -> assertEquals(0, securityHarness.authenticationCalls.get()),
                () -> assertEquals(1, filterChainCalls.get()));
    }

    private LocalDevelopmentAutoSignInFilter filter(
            SecurityHarness securityHarness,
            ApplicationUser admin) {
        UserService userService = new UserService() {
            @Override
            public Optional<ApplicationUser> findByUsername(String username) {
                return "admin".equals(username) ? Optional.of(admin) : Optional.empty();
            }
        };
        return new LocalDevelopmentAutoSignInFilter(
                new LocalDevelopmentAutoSignIn("true", "http://localhost:9080"),
                securityHarness.securityContext,
                userService);
    }

    private static ApplicationUser admin(long passwordVersion) {
        ApplicationUser admin = new ApplicationUser();
        admin.setUsername("admin");
        admin.setDisplayName("Local administrator");
        admin.setPasswordVersion(passwordVersion);
        return admin;
    }

    private static FilterChain countingFilterChain(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }

    private static ResponseHarness response() {
        return new ResponseHarness();
    }

    private static <InterfaceType> InterfaceType interfaceProxy(
            Class<InterfaceType> interfaceType) {
        return createInterfaceProxy(
                interfaceType,
                (ignoredProxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    private static final class SecurityHarness {
        private final AtomicReference<Principal> principal = new AtomicReference<>();
        private final String successfulPrincipalName;
        private final AuthenticationStatus authenticationStatus;
        private final AtomicInteger authenticationCalls = new AtomicInteger();
        private final AtomicReference<AuthenticationParameters> authenticationParameters =
                new AtomicReference<>();
        private final SecurityContext securityContext = createInterfaceProxy(
                SecurityContext.class,
                (ignoredProxy, method, arguments) -> switch (method.getName()) {
                    case "getCallerPrincipal" -> principal.get();
                    case "authenticate" -> authenticate(arguments);
                    case "toString" -> "Security context test proxy";
                    default -> defaultValue(method.getReturnType());
                });

        private SecurityHarness(
                String initialPrincipalName,
                String successfulPrincipalName,
                AuthenticationStatus authenticationStatus) {
            if (initialPrincipalName != null) {
                principal.set(() -> initialPrincipalName);
            }
            this.successfulPrincipalName = successfulPrincipalName;
            this.authenticationStatus = authenticationStatus;
        }

        private AuthenticationStatus authenticate(Object[] arguments) {
            authenticationCalls.incrementAndGet();
            authenticationParameters.set((AuthenticationParameters) arguments[2]);
            if (authenticationStatus == AuthenticationStatus.SUCCESS
                    && successfulPrincipalName != null) {
                principal.set(() -> successfulPrincipalName);
            }
            return authenticationStatus;
        }

        private void clearPrincipal() {
            principal.set(null);
        }
    }

    private static final class RequestHarness {
        private final AtomicReference<SessionHarness> currentSession = new AtomicReference<>();
        private final AtomicInteger logoutCalls = new AtomicInteger();
        private final AtomicInteger sessionInvalidations = new AtomicInteger();
        private final SecurityHarness securityHarness;
        private final HttpServletRequest request;

        private RequestHarness(
                String path,
                Long initialPasswordVersion,
                SecurityHarness securityHarness) {
            this.securityHarness = securityHarness;
            if (initialPasswordVersion != null) {
                SessionHarness initialSession = createSession();
                initialSession.attributes.put(
                        AuthenticatedSessionSecurity.PASSWORD_VERSION_SESSION_ATTRIBUTE,
                        initialPasswordVersion);
            }
            request = createInterfaceProxy(
                    HttpServletRequest.class,
                    (ignoredProxy, method, arguments) -> switch (method.getName()) {
                        case "getMethod" -> "GET";
                        case "getContextPath" -> "";
                        case "getRequestURI" -> path;
                        case "getHeader" -> null;
                        case "getSession" -> getSession(arguments);
                        case "changeSessionId" -> "rotated-session";
                        case "logout" -> logout();
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private HttpSession getSession(Object[] arguments) {
            boolean create = arguments == null
                    || arguments.length == 0
                    || (Boolean) arguments[0];
            SessionHarness existingSession = currentSession.get();
            if (existingSession == null && create) {
                existingSession = createSession();
            }
            return existingSession == null ? null : existingSession.session;
        }

        private SessionHarness createSession() {
            SessionHarness session = new SessionHarness(this);
            currentSession.set(session);
            return session;
        }

        private Object logout() {
            logoutCalls.incrementAndGet();
            securityHarness.clearPrincipal();
            return null;
        }

        private void invalidate(SessionHarness session) {
            sessionInvalidations.incrementAndGet();
            currentSession.compareAndSet(session, null);
        }
    }

    private static final class SessionHarness {
        private final Map<String, Object> attributes = new HashMap<>();
        private final AtomicInteger maximumInactiveInterval = new AtomicInteger();
        private final HttpSession session;

        private SessionHarness(RequestHarness requestHarness) {
            session = createInterfaceProxy(
                    HttpSession.class,
                    (ignoredProxy, method, arguments) -> switch (method.getName()) {
                        case "getAttribute" -> attributes.get((String) arguments[0]);
                        case "setAttribute" -> attributes.put(
                                (String) arguments[0], arguments[1]);
                        case "setMaxInactiveInterval" -> {
                            maximumInactiveInterval.set((Integer) arguments[0]);
                            yield null;
                        }
                        case "invalidate" -> {
                            attributes.clear();
                            requestHarness.invalidate(this);
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class ResponseHarness {
        private final AtomicInteger status = new AtomicInteger();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final HttpServletResponse proxy = createInterfaceProxy(
                HttpServletResponse.class,
                (ignoredProxy, method, arguments) -> switch (method.getName()) {
                    case "setStatus" -> {
                        status.set((Integer) arguments[0]);
                        yield null;
                    }
                    case "setHeader" -> headers.put(
                            (String) arguments[0], (String) arguments[1]);
                    default -> defaultValue(method.getReturnType());
                });
    }
}
