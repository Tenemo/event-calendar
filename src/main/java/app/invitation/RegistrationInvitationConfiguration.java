package app.invitation;

import app.config.ApplicationEnvironmentVariables;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Singleton
@Startup
public class RegistrationInvitationConfiguration {
    private final String bootstrapInvitationToken;

    public RegistrationInvitationConfiguration() {
        this(System.getenv(ApplicationEnvironmentVariables.BOOTSTRAP_INVITATION_TOKEN));
    }

    RegistrationInvitationConfiguration(String configuredBootstrapInvitationToken) {
        bootstrapInvitationToken = InvitationToken.normalize(configuredBootstrapInvitationToken);
    }

    @PostConstruct
    void validate() {
        if (!bootstrapInvitationToken.isEmpty()
                && !InvitationToken.isValidBootstrapSecret(bootstrapInvitationToken)) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_INVITATION_TOKEN must be blank or contain between 43 and 80 Base64URL characters.");
        }
    }

    public boolean matchesBootstrapInvitationToken(String invitationToken) {
        if (bootstrapInvitationToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                bootstrapInvitationToken.getBytes(StandardCharsets.UTF_8),
                invitationToken.getBytes(StandardCharsets.UTF_8));
    }
}
