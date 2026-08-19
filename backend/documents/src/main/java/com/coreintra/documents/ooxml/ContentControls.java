package com.coreintra.documents.ooxml;

import com.coreintra.compat.Immutables;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads and writes Word content controls ({@code w:sdt}) in a document part.
 *
 * <h2>Why content controls and not {@code {{mustache}}}</h2>
 *
 * <p>A content control is a real OOXML structure. It survives a round trip
 * through Word, LibreOffice and 한글 because those applications understand it
 * and preserve it. A mustache token is just text: the first time a client opens
 * a template in Word and edits the paragraph around it, autocorrect, spell-check
 * or a stray run split turns {@code {{amount}}} into {@code {{amo}}{{unt}}} —
 * silently, and the template is broken with no error anywhere.
 *
 * <p>The field's value lives <em>inside</em> the control; the prose around it is
 * ordinary docx and is never touched.
 *
 * <h2>Reading is safe; writing is surgical</h2>
 *
 * <p>Reading parses a DOM and discards it. Writing replaces only the text runs
 * inside the named control and re-serialises only {@code word/document.xml} —
 * every other part in the package stays byte-identical (see
 * {@link OoxmlPackage}).
 */
public final class ContentControls {

    /** The WordprocessingML namespace. */
    public static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private ContentControls() {
    }

    /** One content control found in the document. */
    public static final class Control {
        private final String tag;
        private final String alias;
        private final String text;

        Control(String tag, String alias, String text) {
            this.tag = tag;
            this.alias = alias;
            this.text = text;
        }

        /** The {@code w:tag} value — the field id. */
        public String tag() {
            return tag;
        }

        /** The {@code w:alias} — Word's own display name. Informational. */
        public String alias() {
            return alias;
        }

        /** The current text inside the control. */
        public String text() {
            return text;
        }

        @Override
        public String toString() {
            return tag + "=" + text;
        }
    }

    /**
     * Every content control in the document part, in document order.
     *
     * <p>Controls without a {@code w:tag} are skipped: Word inserts untagged
     * controls for its own purposes, and treating them as fields would produce
     * phantom entries the template author never created.
     */
    public static List<Control> read(byte[] documentPart) {
        Document dom = parse(documentPart);
        List<Control> controls = new ArrayList<Control>();

        NodeList sdtNodes = dom.getElementsByTagNameNS(W_NS, "sdt");
        for (int i = 0; i < sdtNodes.getLength(); i++) {
            Element sdt = (Element) sdtNodes.item(i);
            Element properties = firstChild(sdt, "sdtPr");
            if (properties == null) {
                continue;
            }
            String tag = attributeOfChild(properties, "tag");
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            String alias = attributeOfChild(properties, "alias");
            controls.add(new Control(tag, alias, textOf(firstChild(sdt, "sdtContent"))));
        }
        return Immutables.copyOf(controls);
    }

    /** Tag to text, for the extracted field values stored alongside the document. */
    public static Map<String, String> readValues(byte[] documentPart) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Control control : read(documentPart)) {
            values.put(control.tag(), control.text());
        }
        return values;
    }

    /**
     * Sets the text of the named controls, returning the rewritten part.
     *
     * <p>Replaces the runs inside each control's {@code sdtContent} with a single
     * run carrying the new text, keeping the first run's formatting so a bolded
     * field stays bold.
     *
     * @throws OoxmlException if a requested tag is not present, naming it — a
     *         silent no-op here means a document that looks filled in and is not
     */
    public static byte[] write(byte[] documentPart, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return documentPart;
        }
        Document dom = parse(documentPart);
        Map<String, Boolean> applied = new LinkedHashMap<String, Boolean>();
        for (String tag : values.keySet()) {
            applied.put(tag, Boolean.FALSE);
        }

        NodeList sdtNodes = dom.getElementsByTagNameNS(W_NS, "sdt");
        for (int i = 0; i < sdtNodes.getLength(); i++) {
            Element sdt = (Element) sdtNodes.item(i);
            Element properties = firstChild(sdt, "sdtPr");
            if (properties == null) {
                continue;
            }
            String tag = attributeOfChild(properties, "tag");
            if (tag == null || !values.containsKey(tag)) {
                continue;
            }
            Element content = firstChild(sdt, "sdtContent");
            if (content == null) {
                continue;
            }
            setText(dom, content, values.get(tag));
            applied.put(tag, Boolean.TRUE);
        }

        List<String> missing = new ArrayList<String>();
        for (Map.Entry<String, Boolean> entry : applied.entrySet()) {
            if (!entry.getValue().booleanValue()) {
                missing.add(entry.getKey());
            }
        }
        if (!missing.isEmpty()) {
            throw new OoxmlException(
                    "no content control for field(s) " + missing + " in this document. The "
                            + "template and its field manifest have diverged; re-import the "
                            + "template or correct the manifest.");
        }
        return serialise(dom);
    }

    /**
     * Replaces the text inside a control.
     *
     * <p>Formatting is taken from the first existing run so a bolded or coloured
     * field keeps its appearance. Everything structural inside the control — a
     * table cell wrapper, for instance — is preserved by only touching the runs.
     */
    private static void setText(Document dom, Element content, String text) {
        NodeList paragraphs = content.getElementsByTagNameNS(W_NS, "p");
        Element host = paragraphs.getLength() > 0 ? (Element) paragraphs.item(0) : content;

        Element formattingSource = null;
        NodeList runs = host.getElementsByTagNameNS(W_NS, "r");
        if (runs.getLength() > 0) {
            formattingSource = firstChild((Element) runs.item(0), "rPr");
        }

        List<Node> toRemove = new ArrayList<Node>();
        for (int i = 0; i < host.getChildNodes().getLength(); i++) {
            Node child = host.getChildNodes().item(i);
            if (isW(child, "r")) {
                toRemove.add(child);
            }
        }
        for (Node node : toRemove) {
            host.removeChild(node);
        }

        Element run = dom.createElementNS(W_NS, "w:r");
        if (formattingSource != null) {
            run.appendChild(formattingSource.cloneNode(true));
        }
        Element textElement = dom.createElementNS(W_NS, "w:t");
        // Without xml:space="preserve", Word discards leading and trailing
        // spaces — which silently corrupts any field a user padded.
        textElement.setAttribute("xml:space", "preserve");
        textElement.setTextContent(text == null ? "" : text);
        run.appendChild(textElement);
        host.appendChild(run);
    }

    private static String textOf(Element content) {
        if (content == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        NodeList textNodes = content.getElementsByTagNameNS(W_NS, "t");
        for (int i = 0; i < textNodes.getLength(); i++) {
            text.append(textNodes.item(i).getTextContent());
        }
        return text.toString();
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (isW(child, localName)) {
                return (Element) child;
            }
        }
        return null;
    }

    private static String attributeOfChild(Element properties, String localName) {
        Element child = firstChild(properties, localName);
        return child == null ? null : child.getAttributeNS(W_NS, "val");
    }

    private static boolean isW(Node node, String localName) {
        return node.getNodeType() == Node.ELEMENT_NODE
                && W_NS.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName());
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Client documents are untrusted input. Disabling external entities
            // and DTDs closes XXE, which is otherwise a file-read primitive
            // reachable by anyone who can upload a document.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new OoxmlException("the document body is not readable XML", e);
        }
    }

    private static byte[] serialise(Document dom) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
            // Never indent: added whitespace inside w:t becomes visible text in
            // the rendered document.
            transformer.setOutputProperty(OutputKeys.INDENT, "no");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(dom), new StreamResult(out));
            return out.toString(StandardCharsets.UTF_8.name()).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new OoxmlException("failed to write the document body", e);
        }
    }
}
