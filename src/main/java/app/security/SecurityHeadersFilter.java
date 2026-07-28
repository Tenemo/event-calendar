package app.security;

import app.calendar.CalendarLinkToken;
import app.config.ApplicationEnvironmentVariables;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class SecurityHeadersFilter implements Filter {
    private static final String FACES_RESOURCE_PATH_PREFIX = "/jakarta.faces.resource/";
    private static final String NO_INDEX = "noindex, nofollow";
    private static final String NO_REFERRER = "no-referrer";

    private final boolean nonProductionRailwayEnvironment;

    public SecurityHeadersFilter() {
        this(
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID),
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME));
    }

    SecurityHeadersFilter(String railwayEnvironmentId, String railwayEnvironmentName) {
        nonProductionRailwayEnvironment = railwayEnvironmentId != null
                && !railwayEnvironmentId.isBlank()
                && !"production".equalsIgnoreCase(railwayEnvironmentName);
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        boolean calendarLinkRoute = CalendarLinkToken.fromRequestPath(
                        request.getContextPath(), request.getRequestURI())
                != null;
        boolean invitationTokenQuery = hasQueryParameter(request.getQueryString(), "token");
        DynamicSecurityHeadersResponse securedResponse = new DynamicSecurityHeadersResponse(
                response,
                shouldPreventCaching(request),
                nonProductionRailwayEnvironment || calendarLinkRoute || invitationTokenQuery,
                calendarLinkRoute || invitationTokenQuery);
        securedResponse.apply();
        filterChain.doFilter(new SecureApplicationRequest(request), securedResponse);
    }

    private static boolean shouldPreventCaching(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return requestUri == null
                || contextPath == null
                || !requestUri.startsWith(contextPath + FACES_RESOURCE_PATH_PREFIX);
    }

    private static boolean hasQueryParameter(String queryString, String name) {
        if (queryString == null || queryString.isBlank()) {
            return false;
        }
        for (String part : queryString.split("&")) {
            String encodedName = part.split("=", 2)[0];
            try {
                if (name.equals(URLDecoder.decode(encodedName, StandardCharsets.UTF_8))) {
                    return true;
                }
            } catch (IllegalArgumentException exception) {
                return true;
            }
        }
        return false;
    }

    private static final class SecureApplicationRequest extends HttpServletRequestWrapper {
        private SecureApplicationRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public boolean isSecure() {
            return true;
        }
    }

    private static final class DynamicSecurityHeadersResponse extends HttpServletResponseWrapper {
        private final boolean preventCaching;
        private final boolean preventIndexing;
        private final boolean preventReferrerDisclosure;

        private DynamicSecurityHeadersResponse(
                HttpServletResponse response,
                boolean preventCaching,
                boolean preventIndexing,
                boolean preventReferrerDisclosure) {
            super(response);
            this.preventCaching = preventCaching;
            this.preventIndexing = preventIndexing;
            this.preventReferrerDisclosure = preventReferrerDisclosure;
        }

        @Override
        public void reset() {
            super.reset();
            apply();
        }

        private void apply() {
            if (preventCaching) {
                setHeader("Cache-Control", "no-store");
            }
            if (preventIndexing) {
                setHeader("X-Robots-Tag", NO_INDEX);
            }
            if (preventReferrerDisclosure) {
                setHeader("Referrer-Policy", NO_REFERRER);
            }
        }
    }
}
