package app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ApplicationUrlServiceTest {
    @Test
    void normalizesTheOriginAndBuildsApplicationLinks() {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService(" https://calendar.example:9443/// ");

        applicationUrlService.initialize();

        assertEquals(
                "https://calendar.example:9443/register?token=example",
                applicationUrlService.linkTo("register?token=example"));
    }

    @ParameterizedTest
    @MethodSource("invalidBaseUrls")
    void rejectsValuesThatAreNotAnHttpOrigin(String baseUrl) {
        ApplicationUrlService applicationUrlService = new ApplicationUrlService(baseUrl);

        assertThrows(IllegalStateException.class, applicationUrlService::initialize);
    }

    private static Stream<String> invalidBaseUrls() {
        return Stream.of(
                "",
                "calendar.example",
                "ftp://calendar.example",
                "https://user:password@calendar.example",
                "https://calendar.example/application",
                "https://calendar.example?source=test",
                "https://calendar.example#section");
    }
}
