package app.startup;

import app.config.ApplicationEnvironmentVariables;
import app.fixture.CanonicalCalendarFixture.Scope;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

record VerificationEnvironmentConfiguration(
        EnvironmentKind environmentKind,
        String environmentName,
        String environmentId,
        String username,
        String displayName,
        String password) {
    private static final Pattern PULL_REQUEST_ENVIRONMENT_NAME =
            Pattern.compile("event-calendar-pr-[1-9][0-9]*");
    private static final int MAXIMUM_OWNER_USERNAME_LENGTH = 73;

    static Optional<VerificationEnvironmentConfiguration> fromEnvironment(
            Map<String, String> environment) {
        String environmentName = strippedValue(
                environment, ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME);
        if (isPreviewEnvironment(environmentName)) {
            return Optional.of(new VerificationEnvironmentConfiguration(
                    EnvironmentKind.PREVIEW,
                    environmentName,
                    strippedValue(environment, ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID),
                    requiredUsername(
                            environment,
                            ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME),
                    "Preview user",
                    requiredUnmodifiedValue(
                            environment,
                            ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD)));
        }
        if (!"production".equals(environmentName)) {
            return Optional.empty();
        }

        String enabled = strippedValue(
                environment, ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENABLED);
        if (enabled.isEmpty() || "false".equalsIgnoreCase(enabled)) {
            return Optional.empty();
        }
        if (!"true".equalsIgnoreCase(enabled)) {
            throw new IllegalStateException(
                    "PRODUCTION_VERIFICATION_ENABLED must be true or false when configured.");
        }

        String actualEnvironmentId = requiredStrippedValue(
                environment, ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID);
        String actualProjectId = requiredStrippedValue(
                environment, ApplicationEnvironmentVariables.RAILWAY_PROJECT_ID);
        String expectedProjectId = requiredStrippedValue(
                environment,
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_PROJECT_ID);
        String expectedEnvironmentId = requiredStrippedValue(
                environment,
                ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_ENVIRONMENT_ID);
        if (!actualProjectId.equals(expectedProjectId)
                || !actualEnvironmentId.equals(expectedEnvironmentId)) {
            throw new IllegalStateException(
                    "Production verification provisioning refuses to run outside its configured Railway project and environment.");
        }
        return Optional.of(new VerificationEnvironmentConfiguration(
                EnvironmentKind.PRODUCTION,
                environmentName,
                actualEnvironmentId,
                requiredUsername(
                        environment,
                        ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_USERNAME),
                "Production verification",
                requiredUnmodifiedValue(
                        environment,
                        ApplicationEnvironmentVariables.PRODUCTION_VERIFICATION_PASSWORD)));
    }

    private static boolean isPreviewEnvironment(String environmentName) {
        return "preview-base".equals(environmentName)
                || PULL_REQUEST_ENVIRONMENT_NAME.matcher(environmentName).matches();
    }

    private static String requiredUsername(
            Map<String, String> environment,
            String variableName) {
        String username = requiredStrippedValue(environment, variableName);
        if (username.length() > MAXIMUM_OWNER_USERNAME_LENGTH) {
            throw new IllegalStateException(
                    variableName + " must be 73 characters or fewer so fixture companion accounts remain valid.");
        }
        if (!username.equals(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    variableName + " must already use the lowercase form accepted by sign-in.");
        }
        return username;
    }

    private static String requiredStrippedValue(
            Map<String, String> environment,
            String variableName) {
        String value = strippedValue(environment, variableName);
        if (value.isEmpty()) {
            throw new IllegalStateException(variableName + " is required for verification provisioning.");
        }
        return value;
    }

    private static String strippedValue(Map<String, String> environment, String variableName) {
        String value = environment.get(variableName);
        return value == null ? "" : value.strip();
    }

    private static String requiredUnmodifiedValue(
            Map<String, String> environment,
            String variableName) {
        String value = environment.get(variableName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variableName + " is required for verification provisioning.");
        }
        return value;
    }

    Scope fixtureScope() {
        return environmentKind == EnvironmentKind.PREVIEW ? Scope.PREVIEW : Scope.PRODUCTION;
    }

    boolean permitsNonemptyDatabase() {
        return environmentKind == EnvironmentKind.PRODUCTION;
    }

    String mayaUsername() {
        return username + "-maya";
    }

    String tomaszUsername() {
        return username + "-tomasz";
    }

    @Override
    public String toString() {
        return "VerificationEnvironmentConfiguration[environmentKind="
                + environmentKind
                + ", environmentName="
                + environmentName
                + ", environmentId="
                + environmentId
                + ", username="
                + username
                + ", displayName="
                + displayName
                + ", password=redacted]";
    }

    enum EnvironmentKind {
        PREVIEW,
        PRODUCTION
    }
}
