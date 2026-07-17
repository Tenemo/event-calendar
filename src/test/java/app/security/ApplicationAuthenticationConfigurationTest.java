package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ApplicationAuthenticationConfigurationTest {
    @Test
    void validatesAuthenticationConfigurationDuringApplicationStartup()
            throws NoSuchMethodException {
        assertAll(
                () -> assertTrue(ApplicationAuthenticationConfiguration.class
                        .isAnnotationPresent(Singleton.class)),
                () -> assertTrue(ApplicationAuthenticationConfiguration.class
                        .isAnnotationPresent(Startup.class)),
                () -> assertTrue(ApplicationAuthenticationConfiguration.class
                        .getDeclaredMethod("validate")
                        .isAnnotationPresent(PostConstruct.class)));
    }

    @Test
    void permitsDeterministicDevelopmentPasswordsOnlyOutsideRailway() {
        assertAll(
                () -> assertDoesNotThrow(() -> configuration(null, null).validate()),
                () -> assertDoesNotThrow(() -> configuration("", "").validate()),
                () -> assertDoesNotThrow(() -> configuration(
                                "local-development-only",
                                "   ")
                        .validate()));
    }

    @Test
    void railwayStartupRejectsMissingBlankAndShortPasswordsWithoutDisclosingThem() {
        String sensitiveShortPassword = "do-not-disclose-this-value";

        for (String rejectedPassword : List.of("", "   ")) {
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> configuration(rejectedPassword, "production-environment-id")
                            .validate());
            assertEquals(
                    "APP_LTPA_KEYS_PASSWORD is required in a Railway environment.",
                    exception.getMessage());
        }

        for (String rejectedPassword : List.of(
                sensitiveShortPassword,
                "x".repeat(
                        ApplicationAuthenticationConfiguration
                                        .MINIMUM_LTPA_KEYS_PASSWORD_LENGTH
                                - 1))) {
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> configuration(rejectedPassword, "production-environment-id")
                            .validate());
            assertEquals(
                    "APP_LTPA_KEYS_PASSWORD must contain at least 32 characters in a Railway environment.",
                    exception.getMessage());
            assertFalse(exception.getMessage().contains(rejectedPassword));
        }

        IllegalStateException missingPasswordException = assertThrows(
                IllegalStateException.class,
                () -> configuration(null, "production-environment-id").validate());
        assertEquals(
                "APP_LTPA_KEYS_PASSWORD is required in a Railway environment.",
                missingPasswordException.getMessage());
    }

    @Test
    void railwayStartupAcceptsAStablePasswordAtTheDocumentedMinimum() {
        assertDoesNotThrow(() -> configuration(
                        "0123456789abcdef0123456789ABCDEF",
                        "production-environment-id")
                .validate());
    }

    private static ApplicationAuthenticationConfiguration configuration(
            String configuredPassword,
            String railwayEnvironmentId) {
        return new ApplicationAuthenticationConfiguration(
                configuredPassword,
                railwayEnvironmentId);
    }
}
