package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class ReverseProxyConfigurationTest {
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");

    @Test
    void doesNotRewriteClientAddressOrOriginFromForwardingHeaders() throws Exception {
        NodeList remoteIpConfigurations =
                readServerConfiguration().getElementsByTagName("remoteIp");

        assertEquals(0, remoteIpConfigurations.getLength());
    }

    @Test
    void rejectsWebSpherePrivateProxyHeadersFromEverySource() throws Exception {
        Element httpDispatcher = firstElement(readServerConfiguration(), "httpDispatcher");

        assertEquals("none", httpDispatcher.getAttribute("trustedHeaderOrigin"));
    }

    @Test
    void forcesContainerRedirectsToRemainRelativeWithoutPrivateSslIndicators() throws Exception {
        Element webContainer = firstElement(readServerConfiguration(), "webContainer");

        assertAll(
                () -> assertEquals("true", webContainer.getAttribute("redirectToRelativeUrl")),
                () -> assertEquals("", webContainer.getAttribute("httpsIndicatorHeader")));
    }

    private static Element readServerConfiguration() throws Exception {
        return readXmlRoot(SERVER_CONFIGURATION_PATH);
    }

    private static Element readXmlRoot(Path configurationPath) throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return documentBuilderFactory
                .newDocumentBuilder()
                .parse(configurationPath.toFile())
                .getDocumentElement();
    }

    private static Element firstElement(Element parent, String tagName) {
        Element element = (Element) parent.getElementsByTagName(tagName).item(0);
        assertNotNull(element, () -> "Expected server.xml to contain " + tagName + ".");
        return element;
    }
}
