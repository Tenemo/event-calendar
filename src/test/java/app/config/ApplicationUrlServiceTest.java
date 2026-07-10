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
                "https://calendar.example.com/friends/calendar/token-123",
                applicationUrlService.linkTo("/calendar/token-123"));
        assertEquals(
                "https://calendar.example.com/friends/register?token=invite-123",
                applicationUrlService.linkTo("register?token=invite-123"));
    }

    @Test
    void rejectsConfiguredBaseUrlsThatCouldProduceUntrustedOrMalformedLinks() {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService();

        setField(applicationUrlService, "configuredBaseUrl", "calendar.example.com");
        assertThrows(IllegalStateException.class, () -> applicationUrlService.linkTo("/calendar/token"));

        setField(applicationUrlService, "configuredBaseUrl", "https://calendar.example.com?redirect=attacker.example");
        assertThrows(IllegalStateException.class, () -> applicationUrlService.linkTo("/calendar/token"));
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
}
