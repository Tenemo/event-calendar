package app.config;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ApplicationUrlServiceTest {
    @Test
    void buildsLinksFromTheConfiguredCanonicalBaseUrl() {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService();
        setField(applicationUrlService, "configuredBaseUrl", "https://calendar.example.com/friends/");

        assertEquals(
                "https://calendar.example.com/friends/Abc_123-xY0",
                applicationUrlService.linkTo("/Abc_123-xY0"));
        assertEquals(
                "https://calendar.example.com/friends/register?token=invite-123",
                applicationUrlService.linkTo("register?token=invite-123"));

        setField(applicationUrlService, "configuredBaseUrl", "HTTPS://calendar.example.com:9443/team/");
        assertEquals(
                "HTTPS://calendar.example.com:9443/team/New_123-xY0",
                applicationUrlService.linkTo("New_123-xY0"));

        setField(applicationUrlService, "configuredBaseUrl", "http://127.0.0.1:9080/calendar-app/");
        assertEquals(
                "http://127.0.0.1:9080/calendar-app/register",
                applicationUrlService.linkTo("register"));

        setField(applicationUrlService, "configuredBaseUrl", "https://[2001:db8::1]/friends/");
        assertEquals(
                "https://[2001:db8::1]/friends/Url_123-xY0",
                applicationUrlService.linkTo("Url_123-xY0"));
    }

    @Test
    void rejectsConfiguredBaseUrlsThatCouldProduceUntrustedOrMalformedLinks() {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService();

        setField(applicationUrlService, "configuredBaseUrl", "calendar.example.com");
        assertThrows(IllegalStateException.class, () -> applicationUrlService.linkTo("/Abc_123-xY0"));

        setField(applicationUrlService, "configuredBaseUrl", "https://calendar.example.com?redirect=attacker.example");
        assertThrows(IllegalStateException.class, () -> applicationUrlService.linkTo("/Abc_123-xY0"));

        assertAll(
                () -> assertRejectedBaseUrl(applicationUrlService, "https://user:password@calendar.example.com"),
                () -> assertRejectedBaseUrl(applicationUrlService, "https://calendar.example.com#fragment"),
                () -> assertRejectedBaseUrl(applicationUrlService, "mailto:calendar@example.com"),
                () -> assertRejectedBaseUrl(applicationUrlService, "https:///deployment-path"),
                () -> assertRejectedBaseUrl(applicationUrlService, "https://calendar_example.com"),
                () -> assertRejectedBaseUrl(applicationUrlService, "https://calendar.example.com:"),
                () -> assertRejectedBaseUrl(applicationUrlService, "https://calendar.example.com:65536"),
                () -> assertRejectedBaseUrl(applicationUrlService, "https://[::1"));
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

    private static void assertRejectedBaseUrl(ApplicationUrlService applicationUrlService, String configuredBaseUrl) {
        setField(applicationUrlService, "configuredBaseUrl", configuredBaseUrl);
        assertThrows(IllegalStateException.class, () -> applicationUrlService.linkTo("/Abc_123-xY0"));
    }
}
