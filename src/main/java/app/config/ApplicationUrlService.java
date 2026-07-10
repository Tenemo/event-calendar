package app.config;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import java.net.URI;

@RequestScoped
public class ApplicationUrlService {
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";

    private String configuredBaseUrl = System.getenv(APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE);

    public String linkTo(String path) {
        return baseUrl() + normalizedPath(path);
    }

    private String baseUrl() {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            String normalizedBaseUrl = removeTrailingSlashes(configuredBaseUrl.trim());
            URI baseUri = URI.create(normalizedBaseUrl);
            String scheme = baseUri.getScheme();
            if (("http".equals(scheme) || "https".equals(scheme))
                    && baseUri.getHost() != null
                    && baseUri.getRawQuery() == null
                    && baseUri.getRawFragment() == null) {
                return normalizedBaseUrl;
            }
            throw new IllegalStateException("APP_BASE_URL must be an absolute HTTP or HTTPS URL without a query or fragment.");
        }

        FacesContext facesContext = FacesContext.getCurrentInstance();
        String requestBaseUrl = facesContext.getExternalContext().getRequestScheme()
                + "://"
                + facesContext.getExternalContext().getRequestServerName()
                + requestPort(facesContext)
                + facesContext.getExternalContext().getRequestContextPath();
        return removeTrailingSlashes(requestBaseUrl);
    }

    private String requestPort(FacesContext facesContext) {
        int requestPort = facesContext.getExternalContext().getRequestServerPort();
        String requestScheme = facesContext.getExternalContext().getRequestScheme();
        if ((requestScheme.equals("http") && requestPort == 80)
                || (requestScheme.equals("https") && requestPort == 443)) {
            return "";
        }
        return ":" + requestPort;
    }

    private String normalizedPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }
}
