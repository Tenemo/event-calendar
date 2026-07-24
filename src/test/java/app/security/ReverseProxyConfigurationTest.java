package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class ReverseProxyConfigurationTest {
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");

    @Test
    void doesNotRewriteClientAddressOrOriginFromStandardForwardingHeaders() throws Exception {
        NodeList remoteIpConfigurations =
                readServerConfiguration().getElementsByTagName("remoteIp");

        assertEquals(0, remoteIpConfigurations.getLength());
    }

    @Test
    void trustsPrivateProxyHeadersOnlyFromRailwaysCarrierGradeNatPeers() throws Exception {
        Element httpDispatcher = firstElement(readServerConfiguration(), "httpDispatcher");
        String carrierGradeNatOrigins = IntStream.rangeClosed(64, 127)
                .mapToObj(secondOctet -> "100." + secondOctet + ".*.*")
                .collect(Collectors.joining(","));

        assertEquals(
                carrierGradeNatOrigins,
                httpDispatcher.getAttribute("trustedHeaderOrigin"));
    }

    @Test
    void recognizesRailwaysEdgeMarkerForSslOffloadWhileKeepingRedirectsRelative() throws Exception {
        Element webContainer = firstElement(readServerConfiguration(), "webContainer");

        assertAll(
                () -> assertEquals("true", webContainer.getAttribute("redirectToRelativeUrl")),
                () -> assertEquals(
                        "X-Railway-Edge",
                        webContainer.getAttribute("httpsIndicatorHeader")));
    }

    @Test
    void compressesTextAndCommonWebApplicationMediaTypesWithGzip() throws Exception {
        Element httpEndpoint = firstElement(readServerConfiguration(), "httpEndpoint");
        Element compression = firstElement(httpEndpoint, "compression");
        NodeList configuredTypes = compression.getElementsByTagName("types");
        List<String> mediaTypes = IntStream.range(0, configuredTypes.getLength())
                .mapToObj(index -> configuredTypes.item(index).getTextContent().trim())
                .toList();

        assertAll(
                () -> assertEquals("gzip", compression.getAttribute("serverPreferredAlgorithm")),
                () -> assertEquals(
                        List.of(
                                "+application/*",
                                "+image/svg+xml"),
                        mediaTypes));
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
