package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CalendarLinkRequestThrottleIT {
    private static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9080";
    private static final String APPLICATION_BASE_URL_PROPERTY = "app.baseUrl";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String E2E_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_HEALTH_URL";
    private static final String RATE_LIMIT_RESPONSE_BODY =
            "Too many calendar link requests. Try again later.";
    private static final int MAXIMUM_REQUESTS_PER_SOURCE = 300;
    private static final int MAXIMUM_RETRY_AFTER_SECONDS = 60;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void canonicalRequestsReachTheExactSourceLimitWithoutDisclosingTokens() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        URI applicationBaseUri = resolveApplicationBaseUri();

        for (int requestNumber = 0;
                requestNumber < MAXIMUM_REQUESTS_PER_SOURCE;
                requestNumber++) {
            HttpResponse<String> acceptedResponse = sendCanonicalRequest(
                    httpClient,
                    applicationBaseUri,
                    calendarRequestToken(requestNumber));
            assertEquals(
                    404,
                    acceptedResponse.statusCode(),
                    "Every request within the configured source limit must reach the generic not-found response.");
        }

        String rejectedToken = calendarRequestToken(MAXIMUM_REQUESTS_PER_SOURCE);
        HttpResponse<String> rejectedResponse = sendCanonicalRequest(
                httpClient,
                applicationBaseUri,
                rejectedToken);
        assertGenericRateLimitResponse(rejectedResponse, rejectedToken);
    }

    private static HttpResponse<String> sendCanonicalRequest(
            HttpClient httpClient,
            URI applicationBaseUri,
            String calendarRequestToken) {
        URI requestUri = applicationBaseUri.resolve("/" + calendarRequestToken);
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            return httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fail("Canonical request was interrupted by "
                    + exception.getClass().getSimpleName()
                    + ".");
        } catch (IOException exception) {
            return fail("Canonical request failed with "
                    + exception.getClass().getSimpleName()
                    + ".");
        }
    }

    private static void assertGenericRateLimitResponse(
            HttpResponse<String> rejectedResponse,
            String requestedToken) {
        assertEquals(429, rejectedResponse.statusCode());
        assertFalse(
                rejectedResponse.body().contains(requestedToken),
                "The generic rate-limit body must not disclose the requested calendar token.");
        assertFalse(
                responseHeadersContain(rejectedResponse, requestedToken),
                "Rate-limit headers must not disclose the requested calendar token.");

        List<String> retryAfterValues = rejectedResponse.headers().allValues("Retry-After");
        assertEquals(
                1,
                retryAfterValues.size(),
                "A rate-limit response must carry exactly one Retry-After value.");
        int retryAfterSeconds;
        try {
            retryAfterSeconds = Integer.parseInt(retryAfterValues.getFirst());
        } catch (NumberFormatException exception) {
            fail("Retry-After must be a whole number of seconds.");
            return;
        }

        assertEquals(RATE_LIMIT_RESPONSE_BODY, rejectedResponse.body());
        assertTrue(
                retryAfterSeconds >= 1 && retryAfterSeconds <= MAXIMUM_RETRY_AFTER_SECONDS,
                "Retry-After must remain within the configured one-minute source window.");
        assertTrue(
                rejectedResponse.headers()
                        .allValues("Cache-Control")
                        .stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(value -> value.contains("no-store")),
                "The rate-limit response must not be cacheable.");
    }

    private static boolean responseHeadersContain(
            HttpResponse<?> response,
            String requestedToken) {
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            if (header.getKey().contains(requestedToken)
                    || header.getValue().stream().anyMatch(value -> value.contains(requestedToken))) {
                return true;
            }
        }
        return false;
    }

    private static String calendarRequestToken(int requestNumber) {
        byte[] tokenBytes = ByteBuffer.allocate(Long.BYTES)
                .putLong(Long.MIN_VALUE + requestNumber)
                .array();
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        assertTrue(
                token.matches("[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]"),
                "Every throttle request must use a valid-looking canonical root token.");
        return token;
    }

    private static URI resolveApplicationBaseUri() {
        String configuredBaseUrl = Optional.ofNullable(
                        System.getenv(E2E_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE))
                .filter(value -> !value.isBlank())
                .map(value -> URI.create(value).resolve("/").toString())
                .orElseGet(CalendarLinkRequestThrottleIT::resolveConfiguredApplicationBaseUrl);
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
