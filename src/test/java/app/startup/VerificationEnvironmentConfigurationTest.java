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

class VerificationEnvironmentConfigurationTest {
    private static final String VERIFICATION_PASSWORD =
            "verification account password";
    private static final String PRODUCTION_ENVIRONMENT_ID =
            "1b9180bd-3582-424c-8660-729bb037325e";
    private static final String PRODUCTION_PROJECT_ID =
            "19d4cee8-b374-416a-8103-cfd8e714cb48";

    @Test
    void onlyTheBaseAndCanonicalPullRequestEnvironmentsEnablePreviewProvisioning() {
        for (String environmentName : Set.of("preview-base", "event-calendar-pr-1", "event-calendar-pr-2048")) {
            VerificationEnvironmentConfiguration configuration = VerificationEnvironmentConfiguration
                    .fromEnvironment(previewEnvironment(environmentName))
                    .orElseThrow();

            assertEquals(environmentName, configuration.environmentName());
            assertEquals(
                    VerificationEnvironmentConfiguration.EnvironmentKind.PREVIEW,
                    configuration.environmentKind());
            assertEquals("preview", configuration.username());
            assertEquals("preview-maya", configuration.mayaUsername());
            assertEquals("preview-tomasz", configuration.tomaszUsername());
            assertFalse(configuration.permitsNonemptyDatabase());
        }

        for (String environmentName : Set.of(
                "staging",
                "event-calendar-pr-0",
                "event-calendar-pr-",
                "event-calendar-pr-22-extra",
                "EVENT-CALENDAR-PR-22",
                "preview-base-copy")) {
            assertTrue(VerificationEnvironmentConfiguration
                    .fromEnvironment(previewEnvironment(environmentName))
                    .isEmpty());
        }
        assertTrue(VerificationEnvironmentConfiguration.fromEnvironment(Map.of()).isEmpty());
    }

    @Test
    void productionProvisioningRequiresAnExplicitMatchingRailwayEnvironment() {
        Map<String, String> disabledEnvironment = productionEnvironment();
        disabledEnvironment.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENABLED,
                "false");
        assertTrue(VerificationEnvironmentConfiguration
                .fromEnvironment(disabledEnvironment)
                .isEmpty());

        Map<String, String> missingEnablement = productionEnvironment();
        missingEnablement.remove(ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENABLED);
        assertTrue(VerificationEnvironmentConfiguration
                .fromEnvironment(missingEnablement)
                .isEmpty());

        VerificationEnvironmentConfiguration configuration = VerificationEnvironmentConfiguration
                .fromEnvironment(productionEnvironment())
                .orElseThrow();
        assertEquals(
                VerificationEnvironmentConfiguration.EnvironmentKind.PRODUCTION,
                configuration.environmentKind());
        assertEquals(PRODUCTION_ENVIRONMENT_ID, configuration.environmentId());
        assertEquals("production-verification", configuration.username());
        assertTrue(configuration.permitsNonemptyDatabase());

        Map<String, String> wrongEnvironment = productionEnvironment();
        wrongEnvironment.put(
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID,
                "another-environment-id");
        assertThrows(
                IllegalStateException.class,
                () -> VerificationEnvironmentConfiguration.fromEnvironment(wrongEnvironment));

        Map<String, String> wrongProject = productionEnvironment();
        wrongProject.put(
                ApplicationEnvironmentVariables.RAILWAY_PROJECT_ID,
                "another-project-id");
        assertThrows(
                IllegalStateException.class,
                () -> VerificationEnvironmentConfiguration.fromEnvironment(wrongProject));

        Map<String, String> malformedEnablement = productionEnvironment();
        malformedEnablement.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENABLED,
                "sometimes");
        assertThrows(
                IllegalStateException.class,
                () -> VerificationEnvironmentConfiguration.fromEnvironment(malformedEnablement));
    }

    @Test
    void credentialsAreRequiredAndOwnerUsernameLeavesRoomForCompanionAccounts() {
        for (String missingVariable : Set.of(
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME,
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD)) {
            Map<String, String> environment = new HashMap<>(previewEnvironment("event-calendar-pr-22"));
            environment.remove(missingVariable);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> VerificationEnvironmentConfiguration.fromEnvironment(environment));
            assertTrue(exception.getMessage().contains(missingVariable));
        }

        Map<String, String> longUsernameEnvironment =
                new HashMap<>(previewEnvironment("preview-base"));
        longUsernameEnvironment.put(
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME,
                "x".repeat(74));
        assertThrows(
                IllegalStateException.class,
                () -> VerificationEnvironmentConfiguration.fromEnvironment(longUsernameEnvironment));

        Map<String, String> uppercaseUsernameEnvironment =
                new HashMap<>(previewEnvironment("preview-base"));
        uppercaseUsernameEnvironment.put(
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME,
                "Preview");
        assertThrows(
                IllegalStateException.class,
                () -> VerificationEnvironmentConfiguration.fromEnvironment(uppercaseUsernameEnvironment));
    }

    @Test
    void credentialsAreNotLeakedByConfigurationDiagnostics() {
        VerificationEnvironmentConfiguration configuration = VerificationEnvironmentConfiguration
                .fromEnvironment(previewEnvironment("preview-base"))
                .orElseThrow();

        assertFalse(configuration.toString().contains(VERIFICATION_PASSWORD));
        assertTrue(configuration.toString().contains("password=redacted"));
    }

    @Test
    void startupInvokesProvisioningOnlyForAConfiguredVerificationEnvironment() {
        RecordingProvisioningService provisioningService = new RecordingProvisioningService();
        VerificationEnvironmentSeeder seeder = new VerificationEnvironmentSeeder();
        setField(seeder, "provisioningService", provisioningService);

        seeder.seedFromEnvironment(Map.of(
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME,
                "staging"));
        assertEquals(0, provisioningService.invocationCount);

        seeder.seedFromEnvironment(previewEnvironment("event-calendar-pr-22"));
        assertEquals(1, provisioningService.invocationCount);
        assertEquals("event-calendar-pr-22", provisioningService.environmentName);

        seeder.seedFromEnvironment(productionEnvironment());
        assertEquals(2, provisioningService.invocationCount);
        assertEquals("production", provisioningService.environmentName);
    }

    private static Map<String, String> previewEnvironment(String environmentName) {
        return Map.of(
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME,
                environmentName,
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID,
                "preview-environment-id",
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME,
                "preview",
                ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD,
                VERIFICATION_PASSWORD);
    }

    private static Map<String, String> productionEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put(
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME,
                "production");
        environment.put(
                ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID,
                PRODUCTION_ENVIRONMENT_ID);
        environment.put(
                ApplicationEnvironmentVariables.RAILWAY_PROJECT_ID,
                PRODUCTION_PROJECT_ID);
        environment.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENABLED,
                "true");
        environment.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENVIRONMENT_ID,
                PRODUCTION_ENVIRONMENT_ID);
        environment.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_PROJECT_ID,
                PRODUCTION_PROJECT_ID);
        environment.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_USERNAME,
                "production-verification");
        environment.put(
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_PASSWORD,
                VERIFICATION_PASSWORD);
        return environment;
    }

    private static final class RecordingProvisioningService
            extends VerificationEnvironmentProvisioningService {
        private int invocationCount;
        private String environmentName;

        @Override
        public void ensureProvisioned(VerificationEnvironmentConfiguration configuration) {
            invocationCount++;
            environmentName = configuration.environmentName();
        }
    }
}
