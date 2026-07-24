package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SecurityHeadersEndToEndIT {
    private static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9080";
    private static final String APPLICATION_BASE_URL_PROPERTY = "app.baseUrl";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String E2E_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_HEALTH_URL";
    private static final String CONTENT_SECURITY_POLICY =
            "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";
    private static final String PERMISSIONS_POLICY =
            "camera=(), geolocation=(), microphone=(), payment=(), usb=()";

    @Test
    void dynamicAndStaticResponsesCarryTheirIntendedSecurityAndCachePolicies() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI applicationBaseUri = resolveApplicationBaseUri();

        HttpResponse<String> loginResponse = sendForBody(
                httpClient,
                applicationBaseUri.resolve("/login"));
        HttpResponse<String> statefulFacesResponse = sendForBody(
                httpClient,
                applicationBaseUri.resolve("/register"));
        HttpResponse<Void> forwardedNotFoundResponse = send(
                httpClient,
                applicationBaseUri.resolve("/Abc_123-xY0"));
        HttpResponse<Void> errorResponse = send(
                httpClient,
                applicationBaseUri.resolve("/calendar/Abc_123-xY0"));
        HttpResponse<Void> protectedRedirectResponse = send(
                httpClient,
                applicationBaseUri.resolve("/app/calendars"));
        HttpResponse<Void> privateHeaderRedirectResponse = sendWithLibertyPrivateHeaders(
                httpClient,
                applicationBaseUri.resolve("/app/calendars"));
        HttpResponse<Void> staticResourceResponse = send(
                httpClient,
                applicationBaseUri.resolve(
                        "/jakarta.faces.resource/app.css.xhtml?ln=css"));
        HttpResponse<Void> faviconResponse = send(
                httpClient,
                applicationBaseUri.resolve(
                        "/jakarta.faces.resource/images/favicon.svg.xhtml"));

        assertAll(
                () -> assertEquals(200, loginResponse.statusCode()),
                () -> assertTrue(
                        loginResponse.body().contains(
                                "/jakarta.faces.resource/images/favicon.svg.xhtml"),
                        "Rendered pages must declare the project favicon."),
                () -> assertEquals(200, statefulFacesResponse.statusCode()),
                () -> assertEquals(404, forwardedNotFoundResponse.statusCode()),
                () -> assertEquals(404, errorResponse.statusCode()),
                () -> assertEquals(302, protectedRedirectResponse.statusCode()),
                () -> assertEquals(302, privateHeaderRedirectResponse.statusCode()),
                () -> assertEquals(200, staticResourceResponse.statusCode()),
                () -> assertEquals(200, faviconResponse.statusCode()),
                () -> assertSecurityHeaders(loginResponse),
                () -> assertSecurityHeaders(statefulFacesResponse),
                () -> assertSecurityHeaders(forwardedNotFoundResponse),
                () -> assertSecurityHeaders(errorResponse),
                () -> assertSecurityHeaders(protectedRedirectResponse),
                () -> assertSecurityHeaders(privateHeaderRedirectResponse),
                () -> assertSecurityHeaders(staticResourceResponse),
                () -> assertSecurityHeaders(faviconResponse),
                () -> assertNoStore(loginResponse),
                () -> assertNoStore(statefulFacesResponse),
                () -> assertNoStore(forwardedNotFoundResponse),
                () -> assertNoStore(errorResponse),
                () -> assertNoStore(protectedRedirectResponse),
                () -> assertNoStore(privateHeaderRedirectResponse),
                () -> assertEquals(
                        "/login",
                        requiredHeader(protectedRedirectResponse, "Location")),
                () -> assertEquals(
                        "/login",
                        requiredHeader(privateHeaderRedirectResponse, "Location"),
                        "Client-controlled Liberty private headers must not influence redirect authority."),
                () -> assertNoContainerSavedRequestCookie(protectedRedirectResponse),
                () -> assertNoContainerSavedRequestCookie(privateHeaderRedirectResponse),
                () -> assertFacesCookiePolicy(loginResponse),
                () -> assertFacesCookiePolicy(statefulFacesResponse),
                () -> assertFalse(
                        hasNoStore(staticResourceResponse),
                        "JSF static resources must retain their resource-handler cache policy."),
                () -> assertFalse(
                        hasNoStore(faviconResponse),
                        "The favicon must retain the JSF resource-handler cache policy."));
    }

    private static HttpResponse<String> sendForBody(HttpClient httpClient, URI uri)
            throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<Void> send(HttpClient httpClient, URI uri) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static HttpResponse<Void> sendWithLibertyPrivateHeaders(HttpClient httpClient, URI uri)
            throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(uri)
                        .header("$WSSN", "attacker.invalid")
                        .header("$WSP", "443")
                        .header("$WSSC", "https")
                        .header(
                                "Forwarded",
                                "for=192.0.2.10;proto=https;host=attacker.invalid")
                        .header("X-Forwarded-For", "192.0.2.10")
                        .header("X-Forwarded-Host", "attacker.invalid")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Real-IP", "192.0.2.10")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static void assertSecurityHeaders(HttpResponse<?> response) {
        assertAll(
                () -> assertEquals(
                        CONTENT_SECURITY_POLICY,
                        requiredHeader(response, "Content-Security-Policy")),
                () -> assertEquals("DENY", requiredHeader(response, "X-Frame-Options")),
                () -> assertEquals("nosniff", requiredHeader(response, "X-Content-Type-Options")),
                () -> assertEquals(
                        "strict-origin-when-cross-origin",
                        requiredHeader(response, "Referrer-Policy")),
                () -> assertEquals(
                        PERMISSIONS_POLICY,
                        requiredHeader(response, "Permissions-Policy")),
                () -> assertEquals(
                        "max-age=31536000",
                        requiredHeader(response, "Strict-Transport-Security")));
    }

    private static String requiredHeader(HttpResponse<?> response, String headerName) {
        return response.headers()
                .firstValue(headerName)
                .orElseThrow(() -> new AssertionError(
                        "Response from "
                                + response.uri()
                                + " did not include "
                                + headerName
                                + "."));
    }

    private static void assertNoStore(HttpResponse<?> response) {
        assertTrue(
                hasNoStore(response),
                () -> "Response from "
                        + response.uri()
                        + " did not include Cache-Control: no-store.");
    }

    private static boolean hasNoStore(HttpResponse<?> response) {
        return response.headers()
                .allValues("Cache-Control")
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("no-store"));
    }

    private static void assertNoContainerSavedRequestCookie(HttpResponse<?> response) {
        assertTrue(
                response.headers().allValues("Set-Cookie").stream()
                        .noneMatch(value -> value.toUpperCase(Locale.ROOT).contains("WASREQURL")),
                "Application admission must not create a container saved-request cookie.");
    }

    private static void assertFacesCookiePolicy(HttpResponse<String> response) {
        List<String> cookieHeaders = response.headers().allValues("Set-Cookie");
        String sessionCookie = cookieHeaders.stream()
                .filter(value -> value.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Faces response did not create a session cookie."));
        assertAll(
                () -> assertTrue(sessionCookie.contains("Secure")),
                () -> assertTrue(sessionCookie.contains("HttpOnly")),
                () -> assertTrue(sessionCookie.contains("SameSite=Lax")),
                () -> assertTrue(
                        response.body().contains("cookiesSecure:true"),
                        "PrimeFaces must see the application's unconditional secure-cookie policy."));
    }

    private static URI resolveApplicationBaseUri() {
        String configuredBaseUrl = Optional.ofNullable(
                        System.getenv(E2E_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE))
                .filter(value -> !value.isBlank())
                .map(value -> URI.create(value).resolve("/").toString())
                .orElseGet(() -> resolveConfiguredApplicationBaseUrl());
        String normalizedBaseUrl = configuredBaseUrl.endsWith("/")
                ? configuredBaseUrl
                : configuredBaseUrl + "/";
        return URI.create(normalizedBaseUrl);
    }

    private static String resolveConfiguredApplicationBaseUrl() {
        return Optional.ofNullable(System.getProperty(APPLICATION_BASE_URL_PROPERTY))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> Optional.ofNullable(
                                System.getenv(APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE))
                        .filter(value -> !value.isBlank())
                        .orElse(DEFAULT_APPLICATION_BASE_URL));
    }
}
