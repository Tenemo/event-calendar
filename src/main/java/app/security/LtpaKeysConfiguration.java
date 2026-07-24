package app.security;

import app.config.ApplicationEnvironmentVariables;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

@Singleton
@Startup
public class LtpaKeysConfiguration {
    static final int MINIMUM_LTPA_KEYS_PASSWORD_LENGTH = 32;

    private final String configuredLtpaKeysPassword;
    private final boolean railwayEnvironment;

    public LtpaKeysConfiguration() {
        this(
                System.getenv(ApplicationEnvironmentVariables.LTPA_KEYS_PASSWORD),
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID));
    }

    LtpaKeysConfiguration(
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
