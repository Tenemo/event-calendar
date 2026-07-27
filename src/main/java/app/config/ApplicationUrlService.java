package app.config;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Lock;
import jakarta.ejb.LockType;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import java.net.URI;

@Singleton
@Startup
@Lock(LockType.READ)
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ApplicationUrlService {
    private String baseUrl = System.getenv(ApplicationEnvironmentVariables.APPLICATION_BASE_URL);

    public ApplicationUrlService() {
    }

    ApplicationUrlService(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    void initialize() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("APP_BASE_URL is required.");
        }
        baseUrl = baseUrl.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidBaseUrl();
        }
        String scheme = uri.getScheme();
        if (uri.isOpaque()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw invalidBaseUrl();
        }
    }

    public String linkTo(String path) {
        if (path == null || path.isBlank()) {
            return baseUrl;
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private IllegalStateException invalidBaseUrl() {
        return new IllegalStateException(
                "APP_BASE_URL must be an HTTP or HTTPS origin without credentials, a path, a query, or a fragment.");
    }
}
