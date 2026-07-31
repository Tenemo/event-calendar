package app.security;

import static app.testsupport.ProxyReturnValues.createInterfaceProxy;
import static app.testsupport.ProxyReturnValues.defaultValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class LocalDevelopmentAutoSignInTest {
    @Test
    void missingAndFalseConfigurationRemainDisabledWithoutInspectingTheOrigin() {
        LocalDevelopmentAutoSignIn missingConfiguration =
                new LocalDevelopmentAutoSignIn(null, null);
        LocalDevelopmentAutoSignIn falseConfiguration =
                new LocalDevelopmentAutoSignIn(" FALSE ", "https://calendar.social");

        assertAll(
                () -> assertFalse(missingConfiguration.isEnabled()),
                () -> assertFalse(falseConfiguration.isEnabled()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:9080",
        "https://LOCALHOST/",
        "http://127.0.0.1",
        "https://127.0.0.1:9443/",
        "http://[::1]",
        "https://[::1]:9443/"
    })
    void explicitConfigurationAcceptsOnlyLoopbackOrigins(String applicationBaseUrl) {
        LocalDevelopmentAutoSignIn automaticSignIn =
                new LocalDevelopmentAutoSignIn(" TRUE ", applicationBaseUrl);

        assertTrue(automaticSignIn.isEnabled());
    }

    @ParameterizedTest
    @MethodSource("unsafeOrigins")
    void enabledConfigurationRejectsMissingMalformedAndNonLoopbackOrigins(
            String applicationBaseUrl) {
        assertThrows(
                IllegalStateException.class,
                () -> new LocalDevelopmentAutoSignIn("true", applicationBaseUrl));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "enabled", "true false"})
    void rejectsAmbiguousConfigurationValues(String configuredValue) {
        assertThrows(
                IllegalStateException.class,
                () -> new LocalDevelopmentAutoSignIn(
                        configuredValue, "http://localhost:9080"));
    }

    @ParameterizedTest
    @MethodSource("automaticSignInPages")
    void attemptsOnlyGetRequestsForLocalHomeSignInAndAuthenticatedPages(String path) {
        LocalDevelopmentAutoSignIn automaticSignIn = enabledAutomaticSignIn();

        assertAll(
                () -> assertTrue(automaticSignIn.shouldAttempt(request("GET", path, null))),
                () -> assertFalse(automaticSignIn.shouldAttempt(request("POST", path, null))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/health",
        "/register",
        "/calendar-token",
        "/jakarta.faces.resource/application.css"
    })
    void doesNotAuthenticatePublicOrResourceRequests(String path) {
        assertFalse(enabledAutomaticSignIn().shouldAttempt(request("GET", path, null)));
    }

    @Test
    void explicitSignOutSuppressesAutomaticSignInInALongLivedFreshSession() {
        LocalDevelopmentAutoSignIn automaticSignIn = enabledAutomaticSignIn();
        SessionHarness sessionHarness = new SessionHarness();
        HttpServletRequest request = request("GET", "/app/calendars", sessionHarness);

        automaticSignIn.suppressAfterExplicitSignOut(request);

        assertAll(
                () -> assertFalse(automaticSignIn.shouldAttempt(request)),
                () -> assertEquals(
                        Boolean.TRUE,
                        sessionHarness.attributes.get(
                                LocalDevelopmentAutoSignIn.SUPPRESSION_SESSION_ATTRIBUTE)),
                () -> assertEquals(
                        AuthenticatedSessionSecurity.AUTHENTICATED_SESSION_LIFETIME_SECONDS,
                        sessionHarness.maximumInactiveInterval.get()));
    }

    @Test
    void disabledConfigurationDoesNotCreateASignOutSession() {
        LocalDevelopmentAutoSignIn automaticSignIn =
                new LocalDevelopmentAutoSignIn(null, null);
        HttpServletRequest unexpectedRequest = createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, method, arguments) -> {
                    throw new AssertionError(
                            "Disabled automatic sign-in unexpectedly called " + method.getName());
                });

        automaticSignIn.suppressAfterExplicitSignOut(unexpectedRequest);
    }

    private static Stream<String> unsafeOrigins() {
        return Stream.of(
                null,
                "",
                "localhost:9080",
                "ftp://localhost",
                "http://localhost.example",
                "http://127.0.0.2",
                "http://user:password@localhost",
                "http://localhost/application",
                "http://localhost?test=true",
                "http://localhost#test",
                "not a URI");
    }

    private static Stream<String> automaticSignInPages() {
        return Stream.of("/", "/sign-in", "/sign-in.xhtml", "/app", "/app/calendars");
    }

    private LocalDevelopmentAutoSignIn enabledAutomaticSignIn() {
        return new LocalDevelopmentAutoSignIn("true", "http://localhost:9080");
    }

    private static HttpServletRequest request(
            String method,
            String path,
            SessionHarness sessionHarness) {
        return createInterfaceProxy(
                HttpServletRequest.class,
                (ignoredProxy, invokedMethod, arguments) -> switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getContextPath" -> "";
                    case "getRequestURI" -> path;
                    case "getSession" -> sessionHarness == null ? null : sessionHarness.session;
                    default -> defaultValue(invokedMethod.getReturnType());
                });
    }

    private static final class SessionHarness {
        private final Map<String, Object> attributes = new HashMap<>();
        private final AtomicInteger maximumInactiveInterval = new AtomicInteger();
        private final HttpSession session = createInterfaceProxy(
                HttpSession.class,
                (ignoredProxy, method, arguments) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get((String) arguments[0]);
                    case "setAttribute" -> attributes.put(
                            (String) arguments[0], arguments[1]);
                    case "setMaxInactiveInterval" -> {
                        maximumInactiveInterval.set((Integer) arguments[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }
}
