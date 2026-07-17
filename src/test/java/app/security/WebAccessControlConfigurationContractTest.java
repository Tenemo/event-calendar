package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class WebAccessControlConfigurationContractTest {
    private static final Path WEB_APPLICATION_ROOT = Path.of("src", "main", "webapp");
    private static final Path WEB_CONFIGURATION_PATH = WEB_APPLICATION_ROOT.resolve(
            Path.of("WEB-INF", "web.xml"));

    @Test
    void everyAuthenticatedApplicationPageRemainsBehindTheApplicationAdmissionFilter() throws Exception {
        Document webConfiguration = parseXml(WEB_CONFIGURATION_PATH);
        NodeList filterElements = webConfiguration.getElementsByTagName("filter");
        String admissionFilterName = null;
        for (int filterIndex = 0; filterIndex < filterElements.getLength(); filterIndex++) {
            Element filter = (Element) filterElements.item(filterIndex);
            if (AuthenticatedApplicationFilter.class.getName().equals(
                    textValues(filter, "filter-class").getFirst())) {
                admissionFilterName = textValues(filter, "filter-name").getFirst();
            }
        }
        String requiredFilterName = admissionFilterName;
        List<String> protectedUrlPatterns = new ArrayList<>();
        List<String> dispatchers = new ArrayList<>();
        NodeList filterMappingElements = webConfiguration.getElementsByTagName("filter-mapping");
        for (int mappingIndex = 0; mappingIndex < filterMappingElements.getLength(); mappingIndex++) {
            Element filterMapping = (Element) filterMappingElements.item(mappingIndex);
            if (requiredFilterName != null
                    && textValues(filterMapping, "filter-name").contains(requiredFilterName)) {
                protectedUrlPatterns.addAll(textValues(filterMapping, "url-pattern"));
                dispatchers.addAll(textValues(filterMapping, "dispatcher"));
            }
        }
        List<String> authenticatedPages;
        try (var applicationPagePaths = Files.walk(WEB_APPLICATION_ROOT.resolve("app"))) {
            authenticatedPages = applicationPagePaths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xhtml"))
                    .map(WEB_APPLICATION_ROOT::relativize)
                    .map(Path::toString)
                    .map(path -> "/" + path.replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        assertAll(
                () -> assertFalse(authenticatedPages.isEmpty()),
                () -> assertEquals("Authenticated application filter", requiredFilterName),
                () -> assertEquals(List.of("/app/*"), protectedUrlPatterns),
                () -> assertEquals(List.of("REQUEST", "FORWARD"), dispatchers),
                () -> assertEquals(
                        0,
                        webConfiguration.getElementsByTagName("security-constraint").getLength(),
                        "Container login redirects must remain disabled behind an untrusted proxy."),
                () -> assertEquals(
                        List.of("USER"),
                        textValues(
                                (Element) webConfiguration
                                        .getElementsByTagName("security-role")
                                        .item(0),
                                "role-name"),
                        "The declared role keeps authenticated identity available to application code."));
        for (String authenticatedPage : authenticatedPages) {
            assertTrue(
                    protectedUrlPatterns.stream()
                            .anyMatch(pattern -> matchesServletPathPattern(pattern, authenticatedPage)),
                    () -> "Authenticated page escaped the /app/* admission boundary: "
                            + authenticatedPage);
        }
    }

    private static boolean matchesServletPathPattern(String pattern, String requestPath) {
        if (pattern.endsWith("/*")) {
            String pathPrefix = pattern.substring(0, pattern.length() - 1);
            return requestPath.startsWith(pathPrefix);
        }
        return pattern.equals(requestPath);
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return documentBuilderFactory.newDocumentBuilder().parse(path.toFile());
    }

    private static List<String> textValues(Element parent, String tagName) {
        NodeList elements = parent.getElementsByTagName(tagName);
        List<String> values = new ArrayList<>();
        for (int elementIndex = 0; elementIndex < elements.getLength(); elementIndex++) {
            values.add(elements.item(elementIndex).getTextContent().trim());
        }
        return values;
    }

}
