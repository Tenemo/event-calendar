package app.startup;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.config.ApplicationEnvironmentVariables;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PreviewEnvironmentConfigurationTest {
    private static final String VALID_BOOTSTRAP_INVITATION_TOKEN =
            "preview-bootstrap-token-0000000000000000000";
    private static final String VERIFICATION_PASSWORD =
            "preview verification password";

    @Test
    void onlyTheBaseAndCanonicalPullRequestEnvironmentsEnableProvisioning() {
        for (String environmentName : Set.of("preview-base", "event-calendar-pr-1", "event-calendar-pr-2048")) {
            PreviewEnvironmentConfiguration configuration = PreviewEnvironmentConfiguration
                    .fromEnvironment(environment(environmentName))
                    .orElseThrow();

            assertEquals(environmentName, configuration.environmentName());
            assertEquals("preview", configuration.username());
            assertEquals(VERIFICATION_PASSWORD, configuration.password());
        }

        for (String environmentName : Set.of(
                "production",
                "staging",
                "event-calendar-pr-0",
                "event-calendar-pr-",
                "event-calendar-pr-22-extra",
                "EVENT-CALENDAR-PR-22",
                "preview-base-copy")) {
            assertTrue(PreviewEnvironmentConfiguration
                    .fromEnvironment(environment(environmentName))
                    .isEmpty());
        }
        assertTrue(PreviewEnvironmentConfiguration.fromEnvironment(Map.of()).isEmpty());
    }

    @Test
    void previewCredentialsAndBootstrapTokenAreRequiredAndValidated() {
        for (String missingVariable : Set.of(
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME,
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD,
                ApplicationEnvironmentVariables.BOOTSTRAP_INVITATION_TOKEN)) {
            Map<String, String> environment = new HashMap<>(environment("event-calendar-pr-22"));
            environment.remove(missingVariable);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> PreviewEnvironmentConfiguration.fromEnvironment(environment));
            assertTrue(exception.getMessage().contains(missingVariable));
        }

        Map<String, String> invalidTokenEnvironment =
                new HashMap<>(environment("event-calendar-pr-22"));
        invalidTokenEnvironment.put(
                ApplicationEnvironmentVariables.BOOTSTRAP_INVITATION_TOKEN,
                "too-short");
        assertThrows(
                IllegalStateException.class,
                () -> PreviewEnvironmentConfiguration.fromEnvironment(invalidTokenEnvironment));
    }

    @Test
    void credentialsAreNotLeakedByConfigurationDiagnostics() {
        PreviewEnvironmentConfiguration configuration = PreviewEnvironmentConfiguration
                .fromEnvironment(environment("preview-base"))
                .orElseThrow();

        assertFalse(configuration.toString().contains(VALID_BOOTSTRAP_INVITATION_TOKEN));
        assertFalse(configuration.toString().contains(VERIFICATION_PASSWORD));
        assertTrue(configuration.toString().contains("bootstrapInvitationToken=redacted"));
        assertTrue(configuration.toString().contains("password=redacted"));
    }

    @Test
    void startupInvokesProvisioningOnlyForAConfiguredPreviewEnvironment() {
        RecordingProvisioningService provisioningService = new RecordingProvisioningService();
        PreviewEnvironmentSeeder seeder = new PreviewEnvironmentSeeder();
        setField(seeder, "provisioningService", provisioningService);

        seeder.seedFromEnvironment(environment("production"));
        assertEquals(0, provisioningService.invocationCount);

        seeder.seedFromEnvironment(environment("event-calendar-pr-22"));
        assertEquals(1, provisioningService.invocationCount);
        assertEquals("event-calendar-pr-22", provisioningService.environmentName);
    }

    private static Map<String, String> environment(String environmentName) {
        return Map.of(
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME,
                environmentName,
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME,
                "preview",
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD,
                VERIFICATION_PASSWORD,
                ApplicationEnvironmentVariables.BOOTSTRAP_INVITATION_TOKEN,
                VALID_BOOTSTRAP_INVITATION_TOKEN);
    }

    private static final class RecordingProvisioningService
            extends PreviewEnvironmentProvisioningService {
        private int invocationCount;
        private String environmentName;

        @Override
        public void ensureProvisioned(PreviewEnvironmentConfiguration configuration) {
            invocationCount++;
            environmentName = configuration.environmentName();
        }
    }
}
