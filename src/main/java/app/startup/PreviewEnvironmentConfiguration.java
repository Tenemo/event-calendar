package app.startup;

import app.config.ApplicationEnvironmentVariables;
import app.invitation.InvitationToken;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

record PreviewEnvironmentConfiguration(
        String environmentName,
        String bootstrapInvitationToken,
        String username,
        String password) {
    private static final Pattern PULL_REQUEST_ENVIRONMENT_NAME =
            Pattern.compile("event-calendar-pr-[1-9][0-9]*");

    static Optional<PreviewEnvironmentConfiguration> fromEnvironment(
            Map<String, String> environment) {
        String environmentName = strippedValue(
                environment, ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME);
        if (!isPreviewEnvironment(environmentName)) {
            return Optional.empty();
        }

        String username = requiredStrippedValue(
                environment, ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_USERNAME);
        String password = requiredUnmodifiedValue(
                environment, ApplicationEnvironmentVariables.PREVIEW_VERIFICATION_PASSWORD);
        String bootstrapInvitationToken = InvitationToken.normalize(requiredUnmodifiedValue(
                environment, ApplicationEnvironmentVariables.BOOTSTRAP_INVITATION_TOKEN));
        if (!InvitationToken.isValidBootstrapSecret(bootstrapInvitationToken)) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_INVITATION_TOKEN must contain between 43 and 80 Base64URL characters in preview environments.");
        }

        return Optional.of(new PreviewEnvironmentConfiguration(
                environmentName,
                bootstrapInvitationToken,
                username,
                password));
    }

    private static boolean isPreviewEnvironment(String environmentName) {
        return "preview-base".equals(environmentName)
                || PULL_REQUEST_ENVIRONMENT_NAME.matcher(environmentName).matches();
    }

    private static String requiredStrippedValue(
            Map<String, String> environment, String variableName) {
        String value = strippedValue(environment, variableName);
        if (value.isEmpty()) {
            throw new IllegalStateException(variableName + " is required in preview environments.");
        }
        return value;
    }

    private static String strippedValue(Map<String, String> environment, String variableName) {
        String value = environment.get(variableName);
        return value == null ? "" : value.strip();
    }

    private static String requiredUnmodifiedValue(
            Map<String, String> environment, String variableName) {
        String value = environment.get(variableName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variableName + " is required in preview environments.");
        }
        return value;
    }

    @Override
    public String toString() {
        return "PreviewEnvironmentConfiguration[environmentName="
                + environmentName
                + ", username="
                + username
                + ", bootstrapInvitationToken=redacted, password=redacted]";
    }
}
