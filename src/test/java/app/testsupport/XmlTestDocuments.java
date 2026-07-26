package app.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public final class XmlTestDocuments {
    private XmlTestDocuments() {
    }

    public static Document parseXml(Path path)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        documentBuilderFactory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        documentBuilderFactory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        documentBuilderFactory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        documentBuilderFactory.setExpandEntityReferences(false);
        documentBuilderFactory.setXIncludeAware(false);
        return documentBuilderFactory.newDocumentBuilder().parse(path.toFile());
    }
}
