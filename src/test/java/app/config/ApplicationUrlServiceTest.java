package app.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import org.junit.jupiter.api.Test;

final class ApplicationUrlServiceTest {
    @Test
    void validatesConfigurationDuringApplicationStartup() throws NoSuchMethodException {
        assertAll(
                () -> assertTrue(ApplicationUrlService.class.isAnnotationPresent(Singleton.class)),
                () -> assertTrue(ApplicationUrlService.class.isAnnotationPresent(Startup.class)),
                () -> assertTrue(ApplicationUrlService.class
                        .getDeclaredMethod("initialize")
                        .isAnnotationPresent(PostConstruct.class)));
    }

    @Test
    void buildsLinksFromTheConfiguredCanonicalBaseUrl() {
        ApplicationUrlService applicationUrlService =
                new ApplicationUrlService("https://calendar.example.com/friends/", null);
        applicationUrlService.initialize();

        assertEquals(
                "https://calendar.example.com/friends/Abc_123-xY0",
                applicationUrlService.linkTo("/Abc_123-xY0"));
        assertEquals(
                "https://calendar.example.com/friends/register?token=invite-123",
                applicationUrlService.linkTo("register?token=invite-123"));

        assertAll(
                () -> assertConfiguredLink(
                        "HTTPS://calendar.example.com:9443/team/",
                        "New_123-xY0",
                        "HTTPS://calendar.example.com:9443/team/New_123-xY0"),
                () -> assertConfiguredLink(
                        "http://127.0.0.1:9080/calendar-app/",
                        "register",
                        "http://127.0.0.1:9080/calendar-app/register"),
                () -> assertConfiguredLink(
                        "https://[2001:db8::1]/friends/",
                        "Url_123-xY0",
                        "https://[2001:db8::1]/friends/Url_123-xY0"));
    }

    @Test
    void rejectsConfiguredBaseUrlsThatCouldProduceUntrustedOrMalformedLinks() {
        ApplicationUrlService malformedBaseUrlService =
                new ApplicationUrlService("calendar.example.com", null);
        assertThrows(IllegalStateException.class, malformedBaseUrlService::initialize);

        ApplicationUrlService untrustedQueryService = new ApplicationUrlService(
                "https://calendar.example.com?redirect=attacker.example", null);
        assertThrows(IllegalStateException.class, untrustedQueryService::initialize);

        assertAll(
                () -> assertRejectedBaseUrl("https://user:password@calendar.example.com"),
                () -> assertRejectedBaseUrl("https://calendar.example.com#fragment"),
                () -> assertRejectedBaseUrl("mailto:calendar@example.com"),
                () -> assertRejectedBaseUrl("https:///deployment-path"),
                () -> assertRejectedBaseUrl("https://calendar_example.com"),
                () -> assertRejectedBaseUrl("https://calendar.example.com:"),
                () -> assertRejectedBaseUrl("https://calendar.example.com:65536"),
                () -> assertRejectedBaseUrl("https://[::1"));
    }

    @Test
    void permitsAnAbsentConfiguredBaseUrlForLoopbackDevelopmentRequests() {
        assertAll(
                () -> assertDoesNotThrow(() -> new ApplicationUrlService(null, null).initialize()),
                () -> assertDoesNotThrow(() -> new ApplicationUrlService("", "").initialize()),
                () -> assertDoesNotThrow(() -> new ApplicationUrlService("   ", "   ").initialize()));
    }

    @Test
    void requiresAnExplicitBaseUrlDuringRailwayStartup() {
        assertAll(
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> new ApplicationUrlService(null, "production-environment-id").initialize()),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> new ApplicationUrlService("", "production-environment-id").initialize()),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> new ApplicationUrlService("   ", "production-environment-id").initialize()),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> new ApplicationUrlService(
                                        "http://calendar.example.com", "production-environment-id")
                                .initialize()),
                () -> assertDoesNotThrow(() -> new ApplicationUrlService(
                                "HTTPS://calendar.example.com", "production-environment-id")
                        .initialize()));
    }

    @Test
    void permitsRequestDerivedLinksOnlyForExactLoopbackHosts() {
        assertAll(
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("localhost")),
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("LOCALHOST")),
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("localhost.")),
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("127.0.0.1")),
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("::1")),
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("[::1]")),
                () -> assertTrue(ApplicationUrlService.isLoopbackHost("0:0:0:0:0:0:0:1")),
                () -> assertFalse(ApplicationUrlService.isLoopbackHost(null)),
                () -> assertFalse(ApplicationUrlService.isLoopbackHost("")),
                () -> assertFalse(ApplicationUrlService.isLoopbackHost("calendar.example.com")),
                () -> assertFalse(ApplicationUrlService.isLoopbackHost("localhost.attacker.example")),
                () -> assertFalse(ApplicationUrlService.isLoopbackHost("127.0.0.1.attacker.example")),
                () -> assertEquals("localhost", ApplicationUrlService.hostForUrl("localhost")),
                () -> assertEquals("[::1]", ApplicationUrlService.hostForUrl("::1")),
                () -> assertEquals("[::1]", ApplicationUrlService.hostForUrl("[::1]")),
                () -> assertEquals(
                        "[0:0:0:0:0:0:0:1]", ApplicationUrlService.hostForUrl("0:0:0:0:0:0:0:1")),
                () -> assertEquals(
                        "http://localhost:9080/friends",
                        ApplicationUrlService.requestDerivedBaseUrl("http", "localhost", 9080, "/friends/")),
                () -> assertEquals(
                        "https://[::1]",
                        ApplicationUrlService.requestDerivedBaseUrl("https", "::1", 443, "")),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> ApplicationUrlService.requestDerivedBaseUrl(
                                "https", "calendar.example.com", 443, "")));
    }

    private static void assertRejectedBaseUrl(String configuredBaseUrl) {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService(configuredBaseUrl, null);
        assertThrows(IllegalStateException.class, applicationUrlService::initialize);
    }

    private static void assertConfiguredLink(String configuredBaseUrl, String path, String expectedLink) {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService(configuredBaseUrl, null);
        applicationUrlService.initialize();
        assertEquals(expectedLink, applicationUrlService.linkTo(path));
    }
}
