package app.security;

import static app.testsupport.XmlTestDocuments.parseXml;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.util.TextNormalizer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class HttpRequestSizeConfigurationTest {
    private static final long HTTP_MESSAGE_SIZE_LIMIT_BYTES =
            RequestBodySecurityFilter.MAXIMUM_HTTP_MESSAGE_SIZE_BYTES;
    private static final Path SERVER_CONFIGURATION_PATH = Path.of(
            "src", "main", "liberty", "config", "server.xml");

    @Test
    void libertyBoundsMessagesWithHeadroomForEncodedDescriptionsAndFacesState()
            throws Exception {
        Document serverConfiguration = parseXml(SERVER_CONFIGURATION_PATH);
        Element endpoint = elementWithId(serverConfiguration, "httpEndpoint", "defaultHttpEndpoint");
        Element httpOptions = elementWithId(serverConfiguration, "httpOptions", "boundedHttpOptions");
        long configuredMessageSizeLimit = Long.parseLong(
                httpOptions.getAttribute("MessageSizeLimit"));

        assertAll(
                () -> assertEquals(
                        "boundedHttpOptions",
                        endpoint.getAttribute("httpOptionsRef")),
                () -> assertEquals(
                        HTTP_MESSAGE_SIZE_LIMIT_BYTES,
                        configuredMessageSizeLimit),
                () -> assertEquals(
                        "false",
                        httpOptions.getAttribute("AutoDecompression")),
                () -> assertTrue(
                        configuredMessageSizeLimit
                                >= TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH * 32,
                        "The HTTP ceiling must leave room for UTF-8 percent encoding, other fields, and JSF ViewState."));
    }

    private static Element elementWithId(
            Document document,
            String elementName,
            String expectedId) {
        NodeList elements = document.getElementsByTagName(elementName);
        for (int elementIndex = 0; elementIndex < elements.getLength(); elementIndex++) {
            Element element = (Element) elements.item(elementIndex);
            if (expectedId.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError(
                "Expected " + elementName + " with id " + expectedId + ".");
    }
}
