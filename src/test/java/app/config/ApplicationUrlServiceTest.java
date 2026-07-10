package app.config;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
