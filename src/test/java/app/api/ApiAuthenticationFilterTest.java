package app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ApiAuthenticationFilterTest {
    @Test
    void acceptsOneCaseInsensitiveBearerCredential() {
        assertEquals(
                "calendar_social_api_secret",
                ApiAuthenticationFilter.bearerToken(requestWithAuthorization("bEaReR calendar_social_api_secret"))
                        .orElseThrow());
        assertEquals(
                "token",
                ApiAuthenticationFilter.bearerToken(requestWithAuthorization("Bearer\t token"))
                        .orElseThrow());
    }

    @Test
    void rejectsMissingMalformedAndRepeatedAuthorizationHeaders() {
        assertTrue(ApiAuthenticationFilter.bearerToken(requestWithAuthorization()).isEmpty());
        for (String malformedHeader : List.of(
                "Basic credentials",
                "Bearer",
                "Bearer ",
                "Bearer token with-spaces",
                "Bearer token\r\nForwarded: injected")) {
            assertTrue(
                    ApiAuthenticationFilter.bearerToken(requestWithAuthorization(malformedHeader)).isEmpty(),
                    () -> "Malformed Authorization header was accepted: " + malformedHeader);
        }
        assertTrue(ApiAuthenticationFilter.bearerToken(
                        requestWithAuthorization("Bearer first", "Bearer second"))
                .isEmpty());
    }

    private static ContainerRequestContext requestWithAuthorization(String... headerValues) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        for (String headerValue : headerValues) {
            headers.add(HttpHeaders.AUTHORIZATION, headerValue);
        }
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[] {ContainerRequestContext.class},
                (proxy, method, arguments) -> {
                    if ("getHeaders".equals(method.getName())) {
                        return headers;
                    }
                    throw new AssertionError("Unexpected request context call: " + method.getName());
                });
    }
}
