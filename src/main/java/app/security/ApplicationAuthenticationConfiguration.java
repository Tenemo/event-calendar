package app.security;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

@Singleton
@Startup
public class ApplicationAuthenticationConfiguration {
    static final String LTPA_KEYS_PASSWORD_ENVIRONMENT_VARIABLE =
            "APP_LTPA_KEYS_PASSWORD";
    static final int MINIMUM_LTPA_KEYS_PASSWORD_LENGTH = 32;

    private static final String RAILWAY_ENVIRONMENT_ID_ENVIRONMENT_VARIABLE =
            "RAILWAY_ENVIRONMENT_ID";

    private final String configuredLtpaKeysPassword;
    private final boolean railwayEnvironment;

    public ApplicationAuthenticationConfiguration() {
        this(
                System.getenv(LTPA_KEYS_PASSWORD_ENVIRONMENT_VARIABLE),
                System.getenv(RAILWAY_ENVIRONMENT_ID_ENVIRONMENT_VARIABLE));
    }

    ApplicationAuthenticationConfiguration(
            String configuredLtpaKeysPassword,
            String railwayEnvironmentId) {
        this.configuredLtpaKeysPassword = configuredLtpaKeysPassword;
        railwayEnvironment = railwayEnvironmentId != null
                && !railwayEnvironmentId.isBlank();
    }

    @PostConstruct
    void validate() {
        if (!railwayEnvironment) {
            return;
        }
        if (configuredLtpaKeysPassword == null
                || configuredLtpaKeysPassword.isBlank()) {
            throw new IllegalStateException(
                    "APP_LTPA_KEYS_PASSWORD is required in a Railway environment.");
        }
        if (configuredLtpaKeysPassword.length()
                < MINIMUM_LTPA_KEYS_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "APP_LTPA_KEYS_PASSWORD must contain at least 32 characters in a Railway environment.");
        }
    }
}
