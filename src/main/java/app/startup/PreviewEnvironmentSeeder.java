package app.startup;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.DependsOn;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.logging.Logger;

@Singleton
@Startup
@DependsOn("DatabaseMigration")
public class PreviewEnvironmentSeeder {
    private static final Logger LOGGER =
            Logger.getLogger(PreviewEnvironmentSeeder.class.getName());

    @Inject
    private PreviewEnvironmentProvisioningService provisioningService;

    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void seed() {
        seedFromEnvironment(System.getenv());
    }

    void seedFromEnvironment(Map<String, String> environment) {
        PreviewEnvironmentConfiguration.fromEnvironment(environment).ifPresent(configuration -> {
            provisioningService.ensureProvisioned(configuration);
            LOGGER.info(() -> "Preview verification account is ready in "
                    + configuration.environmentName()
                    + ".");
        });
    }
}
