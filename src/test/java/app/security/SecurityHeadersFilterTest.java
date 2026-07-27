package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void preventsIndexingInEveryNonProductionRailwayEnvironment() throws Exception {
        for (String environmentName : new String[] {null, "preview-base", "event-calendar-pr-19"}) {
            ResponseCapture responseCapture = new ResponseCapture();

            new SecurityHeadersFilter("railway-environment-id", environmentName)
                    .doFilter(
                            new RequestCapture().request(),
                            responseCapture.response(),
                            (request, response) -> {
                                HttpServletResponse httpResponse =
                                        (HttpServletResponse) response;
                                httpResponse.reset();
                                httpResponse.setHeader("X-Robots-Tag", "index, follow");
                                httpResponse.addHeader("X-Robots-Tag", "all");
                            });

            assertEquals(
                    SecurityHeadersFilter.PREVIEW_ROBOTS_POLICY,
                    responseCapture.headers.get("X-Robots-Tag"));
        }
    }

    @Test
    void leavesProductionAndLocalIndexingDecisionsToEachPage() throws Exception {
        for (SecurityHeadersFilter filter : new SecurityHeadersFilter[] {
            new SecurityHeadersFilter(null, null),
            new SecurityHeadersFilter("railway-environment-id", " production ")
        }) {
            ResponseCapture responseCapture = new ResponseCapture();

            filter.doFilter(
                    new RequestCapture().request(),
                    responseCapture.response(),
                    (request, response) -> { });

            assertTrue(!responseCapture.headers.containsKey("X-Robots-Tag"));
        }
    }

    @Test
    void capabilityUrlsNeverSendTheirBearerSecretAsAReferrer() throws Exception {
        for (RequestCapture requestCapture : new RequestCapture[] {
            new RequestCapture("", "/Abc_123-xY0", (String) null),
            new RequestCapture("/shared", "/shared/register", "token=secret"),
            new RequestCapture("", "/register.xhtml", "t%6fken=secret"),
            new RequestCapture("", "/login", "invite=legacy-secret"),
            new RequestCapture("", "/login.xhtml", "token=secret&next=ignored"),
            new RequestCapture("", "/login;matrix", "token=secret"),
            new RequestCapture("", "/login.xhtml;matrix", "token=secret"),
            new RequestCapture("", "/lo%67in", "token=secret"),
            new RequestCapture("", "/register;matrix", "token=secret"),
            new RequestCapture("", "/reg%69ster", "token=secret"),
            new RequestCapture("", "/any-route", "token=secret"),
            new RequestCapture("", "/login", "%ZZ=malformed")
        }) {
            ResponseCapture responseCapture = new ResponseCapture();
            responseCapture.headers.put(
                    "Referrer-Policy",
                    "strict-origin-when-cross-origin");

            new SecurityHeadersFilter().doFilter(
                    requestCapture.request(),
                    responseCapture.response(),
                    (request, response) -> {
                        ((HttpServletResponse) response).reset();
                        ((HttpServletResponse) response).setHeader(
                                "Referrer-Policy",
                                "strict-origin-when-cross-origin");
                    });

            assertEquals(
                    SecurityHeadersFilter.CAPABILITY_REFERRER_POLICY,
                    responseCapture.headers.get("Referrer-Policy"));
        }
    }

    @Test
    void bearerQueriesCannotBeIndexedOrOverriddenInProduction() throws Exception {
        for (RequestCapture requestCapture : new RequestCapture[] {
            new RequestCapture("", "/register", "token=secret"),
            new RequestCapture("", "/register.xhtml", "t%6fken=secret"),
            new RequestCapture("", "/login", "invite=legacy-secret"),
            new RequestCapture("", "/login;matrix", "token=secret"),
            new RequestCapture("", "/login.xhtml;matrix", "invite=secret"),
            new RequestCapture("", "/lo%67in", "token=secret"),
            new RequestCapture("", "/register;matrix", "invite=secret"),
            new RequestCapture("", "/reg%69ster", "token=secret"),
            new RequestCapture("", "/any-route", "token=secret")
        }) {
            ResponseCapture responseCapture = new ResponseCapture();
            responseCapture.headers.put("X-Robots-Tag", "index, follow");

            new SecurityHeadersFilter("railway-environment-id", "production")
                    .doFilter(
                            requestCapture.request(),
                            responseCapture.response(),
                            (request, response) -> {
                                HttpServletResponse httpResponse =
                                        (HttpServletResponse) response;
                                httpResponse.reset();
                                httpResponse.setHeader("X-Robots-Tag", "index, follow");
                                httpResponse.addHeader("X-Robots-Tag", "all");
                            });

            assertAll(
                    () -> assertEquals(
                            SecurityHeadersFilter.PREVIEW_ROBOTS_POLICY,
                            responseCapture.headers.get("X-Robots-Tag")),
                    () -> assertEquals(
                            SecurityHeadersFilter.CAPABILITY_REFERRER_POLICY,
                            responseCapture.headers.get("Referrer-Policy")));
        }
    }

    @Test
    void ordinaryMalformedAndPostbackOnlyAliasesRemainOutsideCapabilityQueryHandling()
            throws Exception {
        for (RequestCapture requestCapture : new RequestCapture[] {
            new RequestCapture("", "/login", (String) null),
            new RequestCapture("", "/register", "not_token=secret"),
            new RequestCapture("", "/register", "invitationToken=secret"),
            new RequestCapture("", "/register", "registrationForm%3AinvitationToken=secret"),
            new RequestCapture("", "/Abc_123-xY1", (String) null)
        }) {
            ResponseCapture responseCapture = new ResponseCapture();

            new SecurityHeadersFilter("railway-environment-id", "production").doFilter(
                    requestCapture.request(),
                    responseCapture.response(),
                    (request, response) -> { });

            assertAll(
                    () -> assertEquals(
                            "strict-origin-when-cross-origin",
                            responseCapture.headers.get("Referrer-Policy"),
                            () -> "Unexpected policy for "
                                    + requestCapture.requestUri
                                    + "?"
                                    + requestCapture.queryString),
                    () -> assertFalse(responseCapture.headers.containsKey("X-Robots-Tag")));
        }
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
        private final String queryString;

        private RequestCapture() {
            this("", "/login", DispatcherType.REQUEST, null, null, null, null);
        }

        private RequestCapture(String contextPath, String requestUri) {
            this(contextPath, requestUri, DispatcherType.REQUEST, null, null, null, null);
        }

        private RequestCapture(
                String contextPath,
                String requestUri,
                String queryString) {
            this(
                    contextPath,
                    requestUri,
                    DispatcherType.REQUEST,
                    null,
                    null,
                    null,
                    queryString);
        }

        private RequestCapture(
                String contextPath,
                String requestUri,
                DispatcherType dispatcherType) {
            this(contextPath, requestUri, dispatcherType, null, null, null, null);
        }

        private RequestCapture(
                String contextPath,
                String requestUri,
                DispatcherType dispatcherType,
                String scheme,
                String serverName,
                String headerValue) {
            this(
                    contextPath,
                    requestUri,
                    dispatcherType,
                    scheme,
                    serverName,
                    headerValue,
                    null);
        }

        private RequestCapture(
                String contextPath,
                String requestUri,
                DispatcherType dispatcherType,
                String scheme,
                String serverName,
                String headerValue,
                String queryString) {
            this.contextPath = contextPath;
            this.requestUri = requestUri;
            this.dispatcherType = dispatcherType;
            this.scheme = scheme;
            this.serverName = serverName;
            this.headerValue = headerValue;
            this.queryString = queryString;
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
                        if (method.getName().equals("getQueryString")) {
                            return queryString;
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
