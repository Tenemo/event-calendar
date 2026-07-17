package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SecurityHeadersFilterTest {
    @Test
    void headersArePresentBeforeDownstreamErrorHandlingWithoutSessionOrCookieSideEffects()
            throws Exception {
        RequestCapture requestCapture = new RequestCapture();
        ResponseCapture responseCapture = new ResponseCapture();

        new SecurityHeadersFilter().doFilter(
                requestCapture.request(),
                responseCapture.response(),
                (request, response) -> {
                    assertSecurityHeaders(responseCapture.headers);
                    ((HttpServletResponse) response).reset();
                    assertSecurityHeaders(responseCapture.headers);
                    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
                });

        assertAll(
                () -> assertEquals(HttpServletResponse.SC_NOT_FOUND, responseCapture.status),
                () -> assertSecurityHeaders(responseCapture.headers),
                () -> assertEquals("no-store", responseCapture.headers.get("Cache-Control")),
                () -> assertEquals(0, requestCapture.sessionAccessCount.get()),
                () -> assertEquals(0, responseCapture.addedCookieCount.get()),
                () -> assertTrue(responseCapture.setCookieHeaders.isEmpty()));
    }

    @Test
    void preexistingAndDownstreamHeaderPoliciesAreNeverOverwritten() throws Exception {
        ResponseCapture responseCapture = new ResponseCapture();
        Map<String, String> preexistingPolicies = Map.of(
                "Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'",
                "Strict-Transport-Security",
                "max-age=63072000; includeSubDomains; preload");
        Map<String, String> downstreamPolicies = Map.of(
                "Cache-Control",
                "private, no-store",
                "X-Frame-Options",
                "DENY",
                "X-Content-Type-Options",
                "nosniff",
                "Referrer-Policy",
                "no-referrer",
                "Permissions-Policy",
                "camera=(), geolocation=(), microphone=(), payment=(), usb=(), fullscreen=()");
        responseCapture.headers.putAll(preexistingPolicies);

        new SecurityHeadersFilter().doFilter(
                new RequestCapture().request(),
                responseCapture.response(),
                (request, response) -> downstreamPolicies.forEach(
                        ((HttpServletResponse) response)::setHeader));

        preexistingPolicies.forEach((headerName, expectedValue) -> assertEquals(
                expectedValue,
                responseCapture.headers.get(headerName),
                () -> headerName + " was overwritten."));
        downstreamPolicies.forEach((headerName, expectedValue) -> assertEquals(
                expectedValue,
                responseCapture.headers.get(headerName),
                () -> headerName + " was overwritten."));
    }

    @Test
    void facesResourcesKeepTheCachePolicySelectedByTheResourceHandler() throws Exception {
        RequestCapture requestCapture = new RequestCapture(
                "/shared",
                "/shared/jakarta.faces.resource/app.css.xhtml");
        ResponseCapture responseCapture = new ResponseCapture();

        new SecurityHeadersFilter().doFilter(
                requestCapture.request(),
                responseCapture.response(),
                (request, response) -> ((HttpServletResponse) response).setHeader(
                        "Cache-Control",
                        "public, max-age=31536000"));

        assertAll(
                () -> assertSecurityHeaders(responseCapture.headers),
                () -> assertEquals(
                        "public, max-age=31536000",
                        responseCapture.headers.get("Cache-Control")),
                () -> assertEquals(0, requestCapture.sessionAccessCount.get()),
                () -> assertEquals(0, responseCapture.addedCookieCount.get()));
    }

    @Test
    void errorDispatchesAreNotCacheableEvenWhenTheOriginalPathWasAResource() throws Exception {
        RequestCapture requestCapture = new RequestCapture(
                "/shared",
                "/shared/jakarta.faces.resource/missing.css.xhtml",
                DispatcherType.ERROR);
        ResponseCapture responseCapture = new ResponseCapture();

        new SecurityHeadersFilter().doFilter(
                requestCapture.request(),
                responseCapture.response(),
                (request, response) -> { });

        assertEquals("no-store", responseCapture.headers.get("Cache-Control"));
    }

    @Test
    void downstreamFacesCodeAlwaysSeesTheUnconditionalSecureCookiePolicy() throws Exception {
        RequestCapture requestCapture = new RequestCapture(
                "",
                "/login",
                DispatcherType.REQUEST,
                "http",
                "private-backend",
                "untrusted-forwarding-value");

        new SecurityHeadersFilter().doFilter(
                requestCapture.request(),
                new ResponseCapture().response(),
                (request, response) -> {
                    HttpServletRequest downstreamRequest = (HttpServletRequest) request;
                    assertAll(
                            () -> assertTrue(
                                    downstreamRequest.isSecure(),
                                    "Faces must create Secure cookies on the private HTTP hop."),
                            () -> assertEquals("http", downstreamRequest.getScheme()),
                            () -> assertEquals(
                                    "private-backend",
                                    downstreamRequest.getServerName()),
                            () -> assertEquals(
                                    "untrusted-forwarding-value",
                                    downstreamRequest.getHeader("Forwarded")));
                });

        assertEquals(0, requestCapture.sessionAccessCount.get());
    }

    private static void assertSecurityHeaders(Map<String, String> headers) {
        assertAll(
                () -> assertEquals(
                        SecurityHeadersFilter.CONTENT_SECURITY_POLICY,
                        headers.get("Content-Security-Policy")),
                () -> assertEquals("DENY", headers.get("X-Frame-Options")),
                () -> assertEquals("nosniff", headers.get("X-Content-Type-Options")),
                () -> assertEquals(
                        "strict-origin-when-cross-origin",
                        headers.get("Referrer-Policy")),
                () -> assertEquals(
                        SecurityHeadersFilter.PERMISSIONS_POLICY,
                        headers.get("Permissions-Policy")),
                () -> assertEquals(
                        SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY,
                        headers.get("Strict-Transport-Security")));
    }

    private static final class RequestCapture {
        private final AtomicInteger sessionAccessCount = new AtomicInteger();
        private final String contextPath;
        private final String requestUri;
        private final DispatcherType dispatcherType;
        private final String scheme;
        private final String serverName;
        private final String headerValue;

        private RequestCapture() {
            this("", "/login", DispatcherType.REQUEST, null, null, null);
        }

        private RequestCapture(String contextPath, String requestUri) {
            this(contextPath, requestUri, DispatcherType.REQUEST, null, null, null);
        }

        private RequestCapture(
                String contextPath,
                String requestUri,
                DispatcherType dispatcherType) {
            this(contextPath, requestUri, dispatcherType, null, null, null);
        }

        private RequestCapture(
                String contextPath,
                String requestUri,
                DispatcherType dispatcherType,
                String scheme,
                String serverName,
                String headerValue) {
            this.contextPath = contextPath;
            this.requestUri = requestUri;
            this.dispatcherType = dispatcherType;
            this.scheme = scheme;
            this.serverName = serverName;
            this.headerValue = headerValue;
        }

        private ServletRequest request() {
            return (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("getContextPath")) {
                            return contextPath;
                        }
                        if (method.getName().equals("getRequestURI")) {
                            return requestUri;
                        }
                        if (method.getName().equals("getDispatcherType")) {
                            return dispatcherType;
                        }
                        if (method.getName().equals("isSecure") && scheme != null) {
                            return false;
                        }
                        if (method.getName().equals("getScheme") && scheme != null) {
                            return scheme;
                        }
                        if (method.getName().equals("getServerName") && serverName != null) {
                            return serverName;
                        }
                        if (method.getName().equals("getHeader") && headerValue != null) {
                            return headerValue;
                        }
                        if (method.getName().startsWith("getSession")) {
                            sessionAccessCount.incrementAndGet();
                        }
                        throw new AssertionError(
                                "Security headers filter accessed request method "
                                        + method.getName()
                                        + ".");
                    });
        }
    }

    private static final class ResponseCapture {
        private final Map<String, String> headers =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final AtomicInteger addedCookieCount = new AtomicInteger();
        private final Map<String, String> setCookieHeaders =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private int status = HttpServletResponse.SC_OK;

        private HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "containsHeader" -> headers.containsKey((String) arguments[0]);
                        case "setHeader" -> setHeader((String) arguments[0], (String) arguments[1]);
                        case "addHeader" -> addHeader((String) arguments[0], (String) arguments[1]);
                        case "addCookie" -> addCookie((Cookie) arguments[0]);
                        case "reset" -> reset();
                        case "sendError" -> sendError((Integer) arguments[0]);
                        case "getStatus" -> status;
                        default -> throw new AssertionError(
                                "Unsupported response method: " + method.getName());
                    });
        }

        private Object setHeader(String name, String value) {
            headers.put(name, value);
            if (name.equalsIgnoreCase("Set-Cookie")) {
                setCookieHeaders.put(name, value);
            }
            return null;
        }

        private Object addHeader(String name, String value) {
            return setHeader(name, value);
        }

        private Object addCookie(Cookie cookie) {
            addedCookieCount.incrementAndGet();
            return null;
        }

        private Object reset() {
            headers.clear();
            setCookieHeaders.clear();
            status = HttpServletResponse.SC_OK;
            return null;
        }

        private Object sendError(int responseStatus) {
            status = responseStatus;
            return null;
        }
    }
}
