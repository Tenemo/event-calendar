package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class RuntimeSessionConfigurationTest {
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");
    private static final Path WEB_CONFIGURATION_PATH = Path.of(
            "src", "main", "webapp", "WEB-INF", "web.xml");

    @Test
    void sessionsUseHardenedCookiesAndThirtyDayLifetimes() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element httpSession = (Element) documentBuilderFactory
                .newDocumentBuilder()
                .parse(SERVER_CONFIGURATION_PATH.toFile())
                .getElementsByTagName("httpSession")
                .item(0);

        assertAll(
                () -> assertEquals("true", httpSession.getAttribute("cookieHttpOnly")),
                () -> assertEquals("true", httpSession.getAttribute("cookieSecure")),
                () -> assertEquals("Lax", httpSession.getAttribute("cookieSameSite")),
                () -> assertEquals("720h", httpSession.getAttribute("cookieMaxAge")),
                () -> assertEquals("720h", httpSession.getAttribute("invalidationTimeout")),
                () -> assertEquals("false", httpSession.getAttribute("urlRewritingEnabled")));
    }

    @Test
    void rollingSessionRefreshRunsBeforeCanonicalCalendarRouting() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList filterMappingElements = documentBuilderFactory
                .newDocumentBuilder()
                .parse(WEB_CONFIGURATION_PATH.toFile())
                .getElementsByTagName("filter-mapping");
        List<String> filterNames = new ArrayList<>();
        List<String> urlPatterns = new ArrayList<>();
        List<String> dispatchers = new ArrayList<>();
        for (int mappingIndex = 0; mappingIndex < filterMappingElements.getLength(); mappingIndex++) {
            Element filterMapping = (Element) filterMappingElements.item(mappingIndex);
            filterNames.add(filterMapping.getElementsByTagName("filter-name").item(0).getTextContent());
            urlPatterns.add(filterMapping.getElementsByTagName("url-pattern").item(0).getTextContent());
            dispatchers.add(filterMapping.getElementsByTagName("dispatcher").item(0).getTextContent());
        }

        assertAll(
                () -> assertEquals(
                        List.of("Session cookie refresh filter", "Calendar route filter"),
                        filterNames),
                () -> assertEquals(List.of("/*", "/*"), urlPatterns),
                () -> assertEquals(List.of("REQUEST", "REQUEST"), dispatchers));
    }
}
