package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClientRequestSourceResolverTest {
    @Test
    void ignoresRailwayClientAddressesOutsideRailway() {
        ClientRequestSourceResolver resolver = new ClientRequestSourceResolver(null);

        assertAll(
                () -> assertEquals(
                        "192.0.2.10",
                        resolver.resolve(request("192.0.2.10", "198.51.100.21"))),
                () -> assertEquals(
                        "192.0.2.11",
                        new ClientRequestSourceResolver("   ")
                                .resolve(request("192.0.2.11", "198.51.100.22"))));
    }

    @Test
    void usesRailwaysDocumentedClientAddressBehindSharedIngress() {
        ClientRequestSourceResolver resolver = new ClientRequestSourceResolver("production-environment-id");

        assertAll(
                () -> assertEquals(
                        "198.51.100.21",
                        resolver.resolve(request(
                                "100.64.4.8",
                                "198.51.100.21"))),
                () -> assertEquals(
                        "203.0.113.77",
                        resolver.resolve(request(
                                "100.64.4.8",
                                "203.0.113.77"))));
    }

    @Test
    void ignoresSpoofedRailwayHeadersFromAnUntrustedImmediatePeer() {
        ClientRequestSourceResolver resolver = new ClientRequestSourceResolver("production-environment-id");

        assertEquals(
                "198.51.100.90",
                resolver.resolve(request("198.51.100.90", "203.0.113.77")));
    }

    @Test
    void rejectsAmbiguousOrMalformedRailwayHeadersInsteadOfCreatingSpoofableKeys() {
        ClientRequestSourceResolver resolver = new ClientRequestSourceResolver("production-environment-id");
        String connectedProxy = "100.64.15.20";

        assertAll(
                () -> assertEquals(
                        connectedProxy,
                        resolver.resolve(request(connectedProxy, "attacker.example"))),
                () -> assertEquals(
                        connectedProxy,
                        resolver.resolve(request(connectedProxy, "999.2.3.4"))),
                () -> assertEquals(
                        connectedProxy,
                        resolver.resolve(request(connectedProxy, "198.51.100.1, 100.64.15.20"))),
                () -> assertEquals(
                        connectedProxy,
                        resolver.resolve(request(connectedProxy, "1".repeat(65)))),
                () -> assertEquals(
                        connectedProxy,
                        resolver.resolve(request(
                                connectedProxy,
                                "198.51.100.1",
                                "203.0.113.1"))));
    }

    @Test
    void canonicalizesNumericAddressesAndFailsClosedWhenNoAddressIsUsable() {
        ClientRequestSourceResolver railwayResolver = new ClientRequestSourceResolver("production-environment-id");
        ClientRequestSourceResolver directResolver = new ClientRequestSourceResolver(null);

        assertAll(
                () -> assertEquals(
                        "198.51.100.7",
                        railwayResolver.resolve(request("100.64.2.3", "198.051.100.007"))),
                () -> assertEquals("<unknown-source>", directResolver.resolve(request("proxy.internal"))),
                () -> assertEquals("<unknown-source>", directResolver.resolve(null)));
    }

    private static HttpServletRequest request(String remoteAddress, String... realIpHeaders) {
        List<String> headerValues = List.of(realIpHeaders);
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getRemoteAddr" -> remoteAddress;
                    case "getHeaders" -> "X-Real-IP".equals(arguments[0])
                            ? Collections.enumeration(headerValues)
                            : Collections.emptyEnumeration();
                    default -> throw new AssertionError("Unsupported request method: " + method.getName());
                });
    }
}
