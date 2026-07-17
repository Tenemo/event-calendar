package app.health;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class RuntimeHealthConfigurationTest {
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");
    private static final Path COMPOSE_CONFIGURATION_PATH = Path.of("docker-compose.yml");
    private static final Pattern SECOND_DURATION_PATTERN = Pattern.compile("(\\d+)s");
    private static final Pattern COMPOSE_TIMEOUT_PATTERN = Pattern.compile(
            "(?m)^x-application-health-timeout: &application-health-timeout (\\d+)s$");

    @Test
    void databaseAcquisitionAndValidationFitWithinTheContainerHealthTimeout() throws Exception {
        Document serverConfiguration = readServerConfiguration();
        Element calendarDataSource = elementWithId(serverConfiguration, "dataSource", "CalendarDataSource");
        Element connectionManager = firstDescendant(calendarDataSource, "connectionManager");
        Element postgresqlProperties = firstDescendant(calendarDataSource, "properties.postgresql");

        Duration connectionAcquisitionTimeout = duration(connectionManager.getAttribute("connectionTimeout"));
        Duration driverConnectTimeout = duration(postgresqlProperties.getAttribute("connectTimeout"));
        Duration driverLoginTimeout = duration(postgresqlProperties.getAttribute("loginTimeout"));
        Duration healthValidationTimeout = Duration.ofSeconds(HealthServlet.DATABASE_VALIDATION_TIMEOUT_SECONDS);
        Duration containerHealthTimeout = applicationContainerHealthTimeout();

        assertAll(
                () -> assertTrue(
                        connectionAcquisitionTimeout.plus(healthValidationTimeout).compareTo(containerHealthTimeout) < 0,
                        "Database acquisition and validation must finish before the container health timeout."),
                () -> assertTrue(
                        driverConnectTimeout.compareTo(connectionAcquisitionTimeout) <= 0,
                        "The driver connect timeout must not exceed the pool acquisition timeout."),
                () -> assertTrue(
                        driverLoginTimeout.compareTo(connectionAcquisitionTimeout) <= 0,
                        "The driver login timeout must not exceed the pool acquisition timeout."));
    }

    @Test
    void httpEndpointLeavesClientSourceResolutionToTheRailwayAwareApplicationPolicy() throws Exception {
        Document serverConfiguration = readServerConfiguration();
        Element httpEndpoint = elementWithId(serverConfiguration, "httpEndpoint", "defaultHttpEndpoint");

        assertEquals(0, httpEndpoint.getElementsByTagName("remoteIp").getLength());
    }

    private static Document readServerConfiguration() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return documentBuilderFactory.newDocumentBuilder().parse(SERVER_CONFIGURATION_PATH.toFile());
    }

    private static Element elementWithId(Document document, String elementName, String expectedId) {
        NodeList matchingElements = document.getElementsByTagName(elementName);
        for (int elementIndex = 0; elementIndex < matchingElements.getLength(); elementIndex++) {
            Element element = (Element) matchingElements.item(elementIndex);
            if (expectedId.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError("Could not find " + elementName + " with id " + expectedId + ".");
    }

    private static Element firstDescendant(Element parent, String elementName) {
        Element descendant = (Element) parent.getElementsByTagName(elementName).item(0);
        assertNotNull(descendant, "Could not find " + elementName + ".");
        return descendant;
    }

    private static Duration applicationContainerHealthTimeout() throws Exception {
        String composeConfiguration = Files.readString(COMPOSE_CONFIGURATION_PATH);
        Matcher timeoutMatcher = COMPOSE_TIMEOUT_PATTERN.matcher(composeConfiguration);
        assertTrue(timeoutMatcher.find(), "Could not find the shared application health timeout.");
        return Duration.ofSeconds(Long.parseLong(timeoutMatcher.group(1)));
    }

    private static Duration duration(String configuredDuration) {
        Matcher durationMatcher = SECOND_DURATION_PATTERN.matcher(configuredDuration);
        assertTrue(durationMatcher.matches(), "Expected a duration expressed in seconds, but got " + configuredDuration + ".");
        return Duration.ofSeconds(Long.parseLong(durationMatcher.group(1)));
    }
}
