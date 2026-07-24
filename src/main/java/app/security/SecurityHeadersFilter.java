package app.security;

import app.config.ApplicationEnvironmentVariables;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

public final class SecurityHeadersFilter implements Filter {
    private static final String FACES_RESOURCE_PATH_PREFIX = "/jakarta.faces.resource/";
    private static final String PRODUCTION_ENVIRONMENT_NAME = "production";

    static final String CONTENT_SECURITY_POLICY =
            "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";
    static final String PERMISSIONS_POLICY =
            "camera=(), geolocation=(), microphone=(), payment=(), usb=()";
    static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000";
    static final String PREVIEW_ROBOTS_POLICY = "noindex, nofollow";

    private final boolean preventSearchEngineIndexing;

    public SecurityHeadersFilter() {
        this(
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID),
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME));
    }

    SecurityHeadersFilter(String railwayEnvironmentId, String railwayEnvironmentName) {
        preventSearchEngineIndexing = railwayEnvironmentId != null
                && !railwayEnvironmentId.isBlank()
                && (railwayEnvironmentName == null
                        || !PRODUCTION_ENVIRONMENT_NAME.equalsIgnoreCase(
                                railwayEnvironmentName.trim()));
    }

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain) throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            SecurityHeadersResponse securityHeadersResponse =
                    new SecurityHeadersResponse(
                            httpResponse,
                            shouldPreventCaching(request),
                            preventSearchEngineIndexing);
            securityHeadersResponse.applySecurityHeaders();
            ServletRequest downstreamRequest = request instanceof HttpServletRequest httpRequest
                    ? new SecureApplicationRequest(httpRequest)
                    : request;
            filterChain.doFilter(downstreamRequest, securityHeadersResponse);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean shouldPreventCaching(ServletRequest request) {
        if (!(request instanceof HttpServletRequest httpRequest)) {
            return true;
        }
        if (httpRequest.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        String contextPath = httpRequest.getContextPath();
        String requestUri = httpRequest.getRequestURI();
        if (contextPath == null
                || requestUri == null
                || !requestUri.startsWith(contextPath)) {
            return true;
        }
        String applicationPath = requestUri.substring(contextPath.length());
        return !applicationPath.startsWith(FACES_RESOURCE_PATH_PREFIX);
    }

    /**
     * Exposes the application's unconditional secure-cookie policy to Faces implementations.
     * The externally visible origin is configured separately and never comes from request headers.
     */
    private static final class SecureApplicationRequest extends HttpServletRequestWrapper {
        private SecureApplicationRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public boolean isSecure() {
            return true;
        }
    }

    private static final class SecurityHeadersResponse extends HttpServletResponseWrapper {
        private final boolean preventCaching;
        private final boolean preventSearchEngineIndexing;

        private SecurityHeadersResponse(
                HttpServletResponse response,
                boolean preventCaching,
                boolean preventSearchEngineIndexing) {
            super(response);
            this.preventCaching = preventCaching;
            this.preventSearchEngineIndexing = preventSearchEngineIndexing;
        }

        @Override
        public void reset() {
            super.reset();
            applySecurityHeaders();
        }

        private void applySecurityHeaders() {
            setHeaderWhenAbsent("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            setHeaderWhenAbsent("X-Frame-Options", "DENY");
            setHeaderWhenAbsent("X-Content-Type-Options", "nosniff");
            setHeaderWhenAbsent("Referrer-Policy", "strict-origin-when-cross-origin");
            setHeaderWhenAbsent("Permissions-Policy", PERMISSIONS_POLICY);
            setHeaderWhenAbsent("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
            if (preventCaching) {
                setHeaderWhenAbsent("Cache-Control", "no-store");
            }
            if (preventSearchEngineIndexing) {
                setHeaderWhenAbsent("X-Robots-Tag", PREVIEW_ROBOTS_POLICY);
            }
        }

        private void setHeaderWhenAbsent(String headerName, String headerValue) {
            if (!containsHeader(headerName)) {
                setHeader(headerName, headerValue);
            }
        }
    }
}
