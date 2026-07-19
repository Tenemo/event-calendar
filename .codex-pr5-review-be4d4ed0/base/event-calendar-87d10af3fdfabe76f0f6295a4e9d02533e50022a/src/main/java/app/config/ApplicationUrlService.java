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
            try {
                URI baseUri = URI.create(normalizedBaseUrl);
                String scheme = baseUri.getScheme();
                if (!baseUri.isOpaque()
                        && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && baseUri.getHost() != null
                        && baseUri.getPort() <= 65_535
                        && !baseUri.getRawAuthority().endsWith(":")
                        && baseUri.getRawUserInfo() == null
                        && baseUri.getRawQuery() == null
                        && baseUri.getRawFragment() == null) {
                    return normalizedBaseUrl;
                }
            } catch (IllegalArgumentException exception) {
                throw invalidBaseUrlException(exception);
            }
            throw invalidBaseUrlException(null);
        }

        FacesContext facesContext = FacesContext.getCurrentInstance();
        return requestDerivedBaseUrl(
                facesContext.getExternalContext().getRequestScheme(),
                facesContext.getExternalContext().getRequestServerName(),
                facesContext.getExternalContext().getRequestServerPort(),
                facesContext.getExternalContext().getRequestContextPath());
    }

    static String requestDerivedBaseUrl(
            String requestScheme, String requestServerName, int requestPort, String requestContextPath) {
        if (!isLoopbackHost(requestServerName)) {
            throw new IllegalStateException("APP_BASE_URL is required when the application is accessed from a non-local host.");
        }
        String requestBaseUrl = requestScheme
                + "://"
                + hostForUrl(requestServerName)
                + requestPort(requestScheme, requestPort)
                + requestContextPath;
        return removeTrailingSlashes(requestBaseUrl);
    }

    static boolean isLoopbackHost(String serverName) {
        if (serverName == null) {
            return false;
        }
        return serverName.equalsIgnoreCase("localhost")
                || serverName.equalsIgnoreCase("localhost.")
                || serverName.equals("127.0.0.1")
                || serverName.equals("::1")
                || serverName.equals("[::1]")
                || serverName.equals("0:0:0:0:0:0:0:1");
    }

    static String hostForUrl(String serverName) {
        if (serverName.contains(":") && !serverName.startsWith("[")) {
            return "[" + serverName + "]";
        }
        return serverName;
    }

    private static String requestPort(String requestScheme, int requestPort) {
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

    private static String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }

    private static IllegalStateException invalidBaseUrlException(IllegalArgumentException cause) {
        String message = "APP_BASE_URL must be an absolute HTTP or HTTPS URL without credentials, a query, or a fragment.";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
