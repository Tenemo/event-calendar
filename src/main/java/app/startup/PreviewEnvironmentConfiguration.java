package app.startup;

import app.config.ApplicationEnvironmentVariables;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

record PreviewEnvironmentConfiguration(
        String environmentName,
        String username,
        String password) {
    private static final Pattern PULL_REQUEST_ENVIRONMENT_NAME =
            Pattern.compile("event-calendar-pr-[1-9][0-9]*");
    private static final int MAXIMUM_OWNER_USERNAME_LENGTH = 73;

    static Optional<PreviewEnvironmentConfiguration> fromEnvironment(
            Map<String, String> environment) {
        String environmentName = strippedValue(
                environment, ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME);
        if (!isPreviewEnvironment(environmentName)) {
            return Optional.empty();
        }

        String username = requiredStrippedValue(
                environment, ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME);
        if (username.length() > MAXIMUM_OWNER_USERNAME_LENGTH) {
            throw new IllegalStateException(
                    "PREVIEW_VERIFICATION_USERNAME must be 73 characters or fewer so fixture companion accounts remain valid.");
        }
        if (!username.equals(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "PREVIEW_VERIFICATION_USERNAME must already use the lowercase form accepted by sign-in.");
        }

        return Optional.of(new PreviewEnvironmentConfiguration(
                environmentName,
                username,
                requiredUnmodifiedValue(
                        environment,
                        ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD)));
    }

    private static boolean isPreviewEnvironment(String environmentName) {
        return "preview-base".equals(environmentName)
                || PULL_REQUEST_ENVIRONMENT_NAME.matcher(environmentName).matches();
    }

    private static String requiredStrippedValue(
            Map<String, String> environment,
            String variableName) {
        String value = strippedValue(environment, variableName);
        if (value.isEmpty()) {
            throw new IllegalStateException(variableName + " is required for preview provisioning.");
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
            throw new IllegalStateException(variableName + " is required for preview provisioning.");
        }
        return value;
    }

    String displayName() {
        return "Preview user";
    }

    String mayaUsername() {
        return username + "-maya";
    }

    String tomaszUsername() {
        return username + "-tomasz";
    }

    @Override
    public String toString() {
        return "PreviewEnvironmentConfiguration[environmentName="
                + environmentName
                + ", username="
                + username
                + ", password=redacted]";
    }
}
