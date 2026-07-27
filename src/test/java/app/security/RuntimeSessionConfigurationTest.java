package app.security;

import static app.testsupport.XmlTestDocuments.parseXml;
import app.config.ApplicationEnvironmentVariables;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class RuntimeSessionConfigurationTest {
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");
    private static final Path WEB_CONFIGURATION_PATH = Path.of(
            "src", "main", "webapp", "WEB-INF", "web.xml");
    private static final Path COMPOSE_CONFIGURATION_PATH = Path.of("docker-compose.yml");
    private static final Path CONTAINER_DEFINITION_PATH = Path.of("Dockerfile");
    private static final Path LOCAL_ENVIRONMENT_EXAMPLE_PATH = Path.of(".env.example");

    @Test
    void ltpaKeysUseAStableRuntimePasswordAndSecureSingleSignOnCookies()
            throws Exception {
        Element serverConfiguration = readXmlRoot(SERVER_CONFIGURATION_PATH);
        Element ltpaConfiguration = firstElement(serverConfiguration, "ltpa");
        Element webApplicationSecurity = firstElement(serverConfiguration, "webAppSecurity");
        Element ltpaPasswordVariable = variable(
                serverConfiguration,
                ApplicationEnvironmentVariables.LTPA_KEYS_PASSWORD);

        assertAll(
                () -> assertEquals(
                        "local-development-only",
                        ltpaPasswordVariable.getAttribute("defaultValue")),
                () -> assertEquals(
                        "${APP_LTPA_KEYS_PASSWORD}",
                        ltpaConfiguration.getAttribute("keysPassword")),
                () -> assertEquals(
                        "true",
                        webApplicationSecurity.getAttribute("httpOnlyCookies")),
                () -> assertEquals(
                        "Lax",
                        webApplicationSecurity.getAttribute("sameSiteCookie")),
                () -> assertEquals(
                        "LtpaToken2",
                        webApplicationSecurity.getAttribute("ssoCookieName")),
                () -> assertEquals(
                        "true",
                        webApplicationSecurity.getAttribute("ssoRequiresSSL")),
                () -> assertEquals(
                        "true",
                        webApplicationSecurity.getAttribute("trackLoggedOutSSOCookies")));
    }

    @Test
    void anonymousSessionsAreBoundedShortLivedAndDoNotReceivePersistentCookies() throws Exception {
        Element httpSession = (Element) parseXml(SERVER_CONFIGURATION_PATH)
                .getElementsByTagName("httpSession")
                .item(0);
        Element webSession = (Element) parseXml(WEB_CONFIGURATION_PATH)
                .getElementsByTagName("session-config")
                .item(0);
        Element cookieConfiguration = (Element) webSession
                .getElementsByTagName("cookie-config")
                .item(0);

        assertAll(
                () -> assertEquals("true", httpSession.getAttribute("cookieHttpOnly")),
                () -> assertEquals("true", httpSession.getAttribute("cookieSecure")),
                () -> assertEquals("Lax", httpSession.getAttribute("cookieSameSite")),
                () -> assertEquals("", httpSession.getAttribute("cookieMaxAge")),
                () -> assertEquals("false", httpSession.getAttribute("allowOverflow")),
                () -> assertEquals("1000", httpSession.getAttribute("maxInMemorySessionCount")),
                () -> assertEquals("10m", httpSession.getAttribute("invalidationTimeout")),
                () -> assertEquals("false", httpSession.getAttribute("urlRewritingEnabled")),
                () -> assertEquals(
                        "10",
                        webSession.getElementsByTagName("session-timeout").item(0).getTextContent()),
                () -> assertEquals(
                        "true",
                        cookieConfiguration.getElementsByTagName("http-only").item(0).getTextContent()),
                () -> assertEquals(
                        "true",
                        cookieConfiguration.getElementsByTagName("secure").item(0).getTextContent()),
                () -> assertEquals(
                        "COOKIE",
                        webSession.getElementsByTagName("tracking-mode").item(0).getTextContent()));
    }

    @Test
    void localBrowserOriginUsesHttpsWhenAuthenticationCookiesRequireSecureTransport() throws Exception {
        Element serverConfiguration = readXmlRoot(SERVER_CONFIGURATION_PATH);
        Element httpEndpoint = firstElement(serverConfiguration, "httpEndpoint");
        Element httpHostVariable = variable(serverConfiguration, "HTTP_HOST");
        Element webApplicationSecurity = firstElement(serverConfiguration, "webAppSecurity");
        String composeConfiguration = Files.readString(COMPOSE_CONFIGURATION_PATH);
        String containerDefinition = Files.readString(CONTAINER_DEFINITION_PATH);
        String localEnvironmentExample = Files.readString(LOCAL_ENVIRONMENT_EXAMPLE_PATH);

        assertAll(
                () -> assertEquals(
                        "true",
                        webApplicationSecurity.getAttribute("ssoRequiresSSL")),
                () -> assertEquals("127.0.0.1", httpHostVariable.getAttribute("defaultValue")),
                () -> assertEquals("${HTTP_HOST}", httpEndpoint.getAttribute("host")),
                () -> assertTrue(
                        containsTrimmedLine(containerDefinition, "ENV HTTP_HOST=*"),
                        "The production container must accept traffic from its container network."),
                () -> assertTrue(
                        containsTrimmedLine(
                                composeConfiguration,
                                "APP_BASE_URL: ${APP_BASE_URL:-https://localhost:9443}"),
                        "The local Compose application must advertise an HTTPS browser origin."),
                () -> assertTrue(
                        containsTrimmedLine(
                                composeConfiguration,
                                "- \"127.0.0.1:${PGPORT:-5432}:5432\""),
                        "The local Compose database must be exposed only on loopback."),
                () -> assertTrue(
                        containsTrimmedLine(
                                composeConfiguration,
                                "- \"127.0.0.1:${PORT:-9080}:9080\""),
                        "The local Compose HTTP listener must be exposed only on loopback."),
                () -> assertTrue(
                        containsTrimmedLine(
                                composeConfiguration,
                                "- \"127.0.0.1:${HTTPS_PORT:-9443}:9443\""),
                        "The local Compose HTTPS listener must be exposed only on loopback."),
                () -> assertTrue(
                        containsTrimmedLine(
                                localEnvironmentExample,
                                "APP_BASE_URL=https://localhost:9443"),
                        "The example local environment must use the HTTPS browser origin."));
    }

    private static Element readXmlRoot(Path configurationPath) throws Exception {
        return parseXml(configurationPath).getDocumentElement();
    }

    private static Element firstElement(Element parent, String tagName) {
        Element element = (Element) parent.getElementsByTagName(tagName).item(0);
        assertNotNull(element, () -> "Expected server.xml to contain " + tagName + ".");
        return element;
    }

    private static Element variable(Element serverConfiguration, String expectedName) {
        NodeList variableElements = serverConfiguration.getElementsByTagName("variable");
        for (int variableIndex = 0;
                variableIndex < variableElements.getLength();
                variableIndex++) {
            Element variable = (Element) variableElements.item(variableIndex);
            if (expectedName.equals(variable.getAttribute("name"))) {
                return variable;
            }
        }
        throw new AssertionError(
                "Expected server.xml to declare the " + expectedName + " variable.");
    }

    private static boolean containsTrimmedLine(String contents, String expectedLine) {
        return contents.lines().map(String::trim).anyMatch(expectedLine::equals);
    }

    @Test
    void containerGeneratedResponsesReceiveTheSameFallbackSecurityHeaders()
            throws Exception {
        Element headers = (Element) parseXml(SERVER_CONFIGURATION_PATH)
                .getElementsByTagName("headers")
                .item(0);
        NodeList setIfMissingElements = headers.getElementsByTagName("setIfMissing");
        List<String> fallbackHeaders = new ArrayList<>();
        for (int headerIndex = 0;
                headerIndex < setIfMissingElements.getLength();
                headerIndex++) {
            fallbackHeaders.add(setIfMissingElements.item(headerIndex).getTextContent());
        }

        assertEquals(
                List.of(
                        "Content-Security-Policy:"
                                + SecurityHeadersFilter.CONTENT_SECURITY_POLICY,
                        "X-Frame-Options:DENY",
                        "X-Content-Type-Options:nosniff",
                        "Referrer-Policy:strict-origin-when-cross-origin",
                        "Permissions-Policy:" + SecurityHeadersFilter.PERMISSIONS_POLICY,
                        "Strict-Transport-Security:"
                                + SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY),
                fallbackHeaders);
    }

    @Test
    void securityHeadersAndCalendarRoutingRunBeforeRollingSessionRefreshAndForwardedRendering() throws Exception {
        NodeList filterMappingElements = parseXml(WEB_CONFIGURATION_PATH)
                .getElementsByTagName("filter-mapping");
        List<String> filterNames = new ArrayList<>();
        List<String> urlPatterns = new ArrayList<>();
        List<List<String>> dispatchers = new ArrayList<>();
        for (int mappingIndex = 0; mappingIndex < filterMappingElements.getLength(); mappingIndex++) {
            Element filterMapping = (Element) filterMappingElements.item(mappingIndex);
            filterNames.add(filterMapping.getElementsByTagName("filter-name").item(0).getTextContent());
            urlPatterns.add(filterMapping.getElementsByTagName("url-pattern").item(0).getTextContent());
            NodeList dispatcherElements = filterMapping.getElementsByTagName("dispatcher");
            List<String> mappingDispatchers = new ArrayList<>();
            for (int dispatcherIndex = 0;
                    dispatcherIndex < dispatcherElements.getLength();
                    dispatcherIndex++) {
                mappingDispatchers.add(dispatcherElements.item(dispatcherIndex).getTextContent());
            }
            dispatchers.add(mappingDispatchers);
        }

        assertAll(
                () -> assertEquals(
                        List.of(
                                "Security headers filter",
                                "Request body security filter",
                                "Calendar route filter",
                                "Session cookie refresh filter",
                                "Authenticated application filter"),
                        filterNames),
                () -> assertEquals(
                        List.of("/*", "/*", "/*", "/*", "/app/*"),
                        urlPatterns),
                () -> assertEquals(
                        List.of(
                                List.of("REQUEST", "FORWARD", "ERROR"),
                                List.of("REQUEST"),
                                List.of("REQUEST"),
                                List.of("REQUEST", "FORWARD"),
                                List.of("REQUEST", "FORWARD")),
                        dispatchers));
    }
}
