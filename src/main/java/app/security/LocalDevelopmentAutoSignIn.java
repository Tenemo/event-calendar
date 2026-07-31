package app.security;

import app.config.ApplicationEnvironmentVariables;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.security.enterprise.credential.Credential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class LocalDevelopmentAutoSignIn {
    static final String ADMIN_USERNAME = "admin";
    static final String SUPPRESSION_SESSION_ATTRIBUTE =
            LocalDevelopmentAutoSignIn.class.getName() + ".suppressed";
    private static final Set<String> LOOPBACK_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private String configuredValue =
            System.getenv(ApplicationEnvironmentVariables.LOCAL_AUTO_SIGN_IN);
    private String applicationBaseUrl =
            System.getenv(ApplicationEnvironmentVariables.APPLICATION_BASE_URL);
    private boolean enabled;

    public LocalDevelopmentAutoSignIn() {
    }

    LocalDevelopmentAutoSignIn(String configuredValue, String applicationBaseUrl) {
        this.configuredValue = configuredValue;
        this.applicationBaseUrl = applicationBaseUrl;
        initialize();
    }

    @PostConstruct
    void initialize() {
        String normalizedValue = configuredValue == null
                ? ""
                : configuredValue.trim().toLowerCase(Locale.ROOT);
        if (normalizedValue.isEmpty() || "false".equals(normalizedValue)) {
            enabled = false;
            return;
        }
        if (!"true".equals(normalizedValue)) {
            throw new IllegalStateException(
                    "APP_LOCAL_AUTO_SIGN_IN must be either true or false.");
        }
        if (!isLoopbackOrigin(applicationBaseUrl)) {
            throw new IllegalStateException(
                    "APP_LOCAL_AUTO_SIGN_IN can be enabled only when APP_BASE_URL is a loopback HTTP or HTTPS origin.");
        }
        enabled = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAdminUsername() {
        return ADMIN_USERNAME;
    }

    public Credential createCredential() {
        return new LocalDevelopmentAutoSignInCredential();
    }

    public boolean shouldAttempt(HttpServletRequest request) {
        if (!enabled
                || request == null
                || !"GET".equalsIgnoreCase(request.getMethod())
                || !isAutomaticSignInPath(request)) {
            return false;
        }
        HttpSession session = request.getSession(false);
        return session == null
                || !Boolean.TRUE.equals(session.getAttribute(SUPPRESSION_SESSION_ATTRIBUTE));
    }

    public void suppressAfterExplicitSignOut(HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        HttpSession session = request.getSession(true);
        if (session == null) {
            throw new IllegalStateException(
                    "The servlet container did not create the local sign-out session.");
        }
        session.setMaxInactiveInterval(
                AuthenticatedSessionSecurity.AUTHENTICATED_SESSION_LIFETIME_SECONDS);
        session.setAttribute(SUPPRESSION_SESSION_ATTRIBUTE, Boolean.TRUE);
    }

    public boolean isSignInPage(HttpServletRequest request) {
        String path = contextRelativePath(request);
        return "/sign-in".equals(path) || "/sign-in.xhtml".equals(path);
    }

    private boolean isAutomaticSignInPath(HttpServletRequest request) {
        String path = contextRelativePath(request);
        return "/".equals(path)
                || "/sign-in".equals(path)
                || "/sign-in.xhtml".equals(path)
                || "/app".equals(path)
                || (path != null && path.startsWith("/app/"));
    }

    private String contextRelativePath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri == null || contextPath == null || !requestUri.startsWith(contextPath)) {
            return null;
        }
        return requestUri.substring(contextPath.length());
    }

    private boolean isLoopbackOrigin(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(configuredBaseUrl.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String scheme = uri.getScheme();
        String path = uri.getRawPath();
        String host = uri.getHost();
        return !uri.isOpaque()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && host != null
                && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (path == null || path.isEmpty() || "/".equals(path));
    }
}
