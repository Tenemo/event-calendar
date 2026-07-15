package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

final class RuntimeSessionConfigurationTest {
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");

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
                () -> assertEquals("${COOKIE_SECURE}", httpSession.getAttribute("cookieSecure")),
                () -> assertEquals("Lax", httpSession.getAttribute("cookieSameSite")),
                () -> assertEquals("720h", httpSession.getAttribute("cookieMaxAge")),
                () -> assertEquals("720h", httpSession.getAttribute("invalidationTimeout")),
                () -> assertEquals("false", httpSession.getAttribute("urlRewritingEnabled")));
    }
}
