package com.coreintra.documents.ooxml;

import com.coreintra.compat.Texts;
import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.InternalDoc;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads a DOCX body into {@link InternalDoc}.
 *
 * <h2>Why this is needed when the package is already preserved</h2>
 *
 * <p>{@code DocxAdapter} keeps the whole original package as an opaque block,
 * which makes {@code docx → … → docx} lossless. It does not make
 * {@code docx → HWPX} <em>useful</em>: without a parsed body, the HWPX would
 * come out carrying the field values and a sidecar, and none of the prose. The
 * Korean client opening it would see an empty document.
 *
 * <p>So the body is parsed as well as preserved. The two coexist: the parsed
 * blocks are what another format is built from, and the preserved package is
 * what a round trip back to DOCX replays.
 *
 * <h2>The subset, and what happens outside it</h2>
 *
 * <p>Paragraphs, runs with bold/italic/underline/strikethrough, heading styles,
 * numbered and bulleted lists, tables, page breaks, content controls and inline
 * images. Anything else is skipped here rather than guessed at — and is not
 * lost, because the package it came from is still attached.
 */
public final class DocxReader {

    private static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private final OoxmlPackage document;
    private final BinaryStore binaries;
    private final Map<String, String> relationships;

    public DocxReader(OoxmlPackage document, BinaryStore binaries) {
        this.document = document;
        this.binaries = binaries;
        this.relationships = readRelationships(document);
    }

    /** The body, in document order. */
    public List<InternalDoc.Block> readBlocks() {
        Document dom = parse(document.documentPart());
        List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
        NodeList bodies = dom.getElementsByTagNameNS(W_NS, "body");
        if (bodies.getLength() == 0) {
            return blocks;
        }
        readChildren((Element) bodies.item(0), blocks);
        return blocks;
    }

    private void readChildren(Element parent, List<InternalDoc.Block> into) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() != Node.ELEMENT_NODE
                    || !W_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String name = child.getLocalName();
            if ("p".equals(name)) {
                readParagraph((Element) child, into);
            } else if ("tbl".equals(name)) {
                into.add(readTable((Element) child));
            } else if ("sdt".equals(name)) {
                readContentControl((Element) child, into);
            }
        }
    }

    /**
     * A block-level content control.
     *
     * <p>A control holding one paragraph of text is a typed field. A control
     * holding a table is something structural bound as a whole — a 결재란 — and
     * has to keep its shape, so it becomes a
     * {@link InternalDoc.ControlBlock} rather than being flattened to the text
     * inside it.
     */
    private void readContentControl(Element sdt, List<InternalDoc.Block> into) {
        Element properties = firstChild(sdt, "sdtPr");
        String tag = properties == null ? null : attributeOfChild(properties, "tag");
        Element content = firstChild(sdt, "sdtContent");
        if (tag == null || Texts.isBlank(tag) || content == null) {
            // Word inserts untagged controls for its own purposes; treating one
            // as a field would invent a phantom entry in the manifest.
            if (content != null) {
                readChildren(content, into);
            }
            return;
        }
        String alias = attributeOfChild(properties, "alias");
        List<InternalDoc.Block> inner = new ArrayList<InternalDoc.Block>();
        readChildren(content, inner);
        // An inline control holds its runs directly, with no w:p around them —
        // which is what Word writes for a field inside a sentence, and what the
        // 휴가신청서 fixture uses. Reading only block children would return the
        // tag with an empty value, so the binding would survive and the data
        // would not.
        List<InternalDoc.Run> looseRuns = new ArrayList<InternalDoc.Run>();
        NodeList direct = content.getChildNodes();
        for (int index = 0; index < direct.getLength(); index++) {
            if (isW(direct.item(index), "r")) {
                readRun((Element) direct.item(index), looseRuns, inner);
            }
        }
        if (!looseRuns.isEmpty()) {
            inner.add(new InternalDoc.Paragraph(looseRuns, 0, null, 0));
        }

        boolean onlyProse = true;
        for (InternalDoc.Block block : inner) {
            if (!(block instanceof InternalDoc.Paragraph)) {
                onlyProse = false;
                break;
            }
        }
        if (onlyProse) {
            StringBuilder text = new StringBuilder();
            for (InternalDoc.Block block : inner) {
                text.append(((InternalDoc.Paragraph) block).text());
            }
            into.add(new InternalDoc.FieldBlock(tag, text.toString()));
            return;
        }
        into.add(new InternalDoc.ControlBlock(tag, alias, inner));
    }

    private void readParagraph(Element paragraph, List<InternalDoc.Block> into) {
        Element properties = firstChild(paragraph, "pPr");
        int headingLevel = headingLevelOf(properties);
        String listStyle = listStyleOf(properties);
        int listLevel = listLevelOf(properties);

        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
        NodeList children = paragraph.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() != Node.ELEMENT_NODE
                    || !W_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String name = child.getLocalName();
            if ("r".equals(name)) {
                readRun((Element) child, runs, into);
            } else if ("sdt".equals(name)) {
                // An inline control. Flushing first keeps document order.
                flush(runs, headingLevel, listStyle, listLevel, into);
                readContentControl((Element) child, into);
            } else if ("hyperlink".equals(name)) {
                NodeList linkRuns = ((Element) child).getElementsByTagNameNS(W_NS, "r");
                for (int link = 0; link < linkRuns.getLength(); link++) {
                    readRun((Element) linkRuns.item(link), runs, into);
                }
            }
        }
        flush(runs, headingLevel, listStyle, listLevel, into);
    }

    private void flush(List<InternalDoc.Run> runs, int headingLevel, String listStyle,
            int listLevel, List<InternalDoc.Block> into) {
        if (runs.isEmpty()) {
            return;
        }
        into.add(new InternalDoc.Paragraph(new ArrayList<InternalDoc.Run>(runs),
                headingLevel, listStyle, listLevel));
        runs.clear();
    }

    private void readRun(Element run, List<InternalDoc.Run> runs,
            List<InternalDoc.Block> into) {
        Element properties = firstChild(run, "rPr");
        boolean bold = hasChild(properties, "b");
        boolean italic = hasChild(properties, "i");
        boolean underline = hasUnderline(properties);
        boolean strike = hasChild(properties, "strike");
        String font = fontOf(properties);

        NodeList children = run.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() != Node.ELEMENT_NODE
                    || !W_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String name = child.getLocalName();
            if ("t".equals(name)) {
                text.append(child.getTextContent());
            } else if ("tab".equals(name)) {
                text.append('\t');
            } else if ("br".equals(name)) {
                String type = ((Element) child).getAttributeNS(W_NS, "type");
                if ("page".equals(type)) {
                    if (text.length() > 0) {
                        runs.add(new InternalDoc.Run(text.toString(), bold, italic, underline,
                                strike, font));
                        text.setLength(0);
                    }
                    flush(runs, 0, null, 0, into);
                    into.add(new InternalDoc.PageBreak());
                } else {
                    text.append('\n');
                }
            } else if ("drawing".equals(name)) {
                if (text.length() > 0) {
                    runs.add(new InternalDoc.Run(text.toString(), bold, italic, underline,
                            strike, font));
                    text.setLength(0);
                }
                flush(runs, 0, null, 0, into);
                InternalDoc.Block image = readDrawing((Element) child);
                if (image != null) {
                    into.add(image);
                }
            }
        }
        if (text.length() > 0) {
            runs.add(new InternalDoc.Run(text.toString(), bold, italic, underline, strike, font));
        }
    }

    /**
     * An inline picture.
     *
     * <p>Needs three things agreeing with each other: the {@code r:embed} on the
     * blip, the relationship it names, and the part that relationship targets.
     * If any of them is missing the drawing is recorded as opaque rather than
     * dropped, because a document with a hole where a 도장 was is worse than one
     * that says a picture could not be read.
     */
    private InternalDoc.Block readDrawing(Element drawing) {
        NodeList blips = drawing.getElementsByTagNameNS(A_NS, "blip");
        if (blips.getLength() == 0) {
            return new InternalDoc.OpaqueBlock("docx", "drawing", null);
        }
        String relationshipId = ((Element) blips.item(0)).getAttributeNS(R_NS, "embed");
        String target = relationships.get(relationshipId);
        if (target == null || binaries == null) {
            return new InternalDoc.OpaqueBlock("docx", "image", null);
        }
        String partName = target.startsWith("/") ? target.substring(1) : "word/" + target;
        if (!document.hasPart(partName)) {
            return new InternalDoc.OpaqueBlock("docx", "image", null);
        }
        byte[] content = document.part(partName);
        String mediaType = mediaTypeOf(partName);
        String sha256 = binaries.store(content, mediaType);

        long width = 0;
        long height = 0;
        NodeList extents = drawing.getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
                "extent");
        if (extents.getLength() > 0) {
            width = parseLong(((Element) extents.item(0)).getAttribute("cx"));
            height = parseLong(((Element) extents.item(0)).getAttribute("cy"));
        }
        String description = "";
        NodeList docPr = drawing.getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
                "docPr");
        if (docPr.getLength() > 0) {
            description = ((Element) docPr.item(0)).getAttribute("descr");
        }
        return new InternalDoc.Image(sha256, mediaType, description,
                (int) Math.min(Integer.MAX_VALUE, width),
                (int) Math.min(Integer.MAX_VALUE, height));
    }

    private InternalDoc.Table readTable(Element table) {
        List<List<List<InternalDoc.Block>>> rows =
                new ArrayList<List<List<InternalDoc.Block>>>();
        boolean headerRow = false;
        NodeList children = table.getChildNodes();
        boolean first = true;
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!isW(child, "tr")) {
                continue;
            }
            Element tr = (Element) child;
            if (first) {
                Element rowProperties = firstChild(tr, "trPr");
                headerRow = hasChild(rowProperties, "tblHeader");
                first = false;
            }
            List<List<InternalDoc.Block>> row = new ArrayList<List<InternalDoc.Block>>();
            NodeList cells = tr.getChildNodes();
            for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
                Node cell = cells.item(cellIndex);
                if (!isW(cell, "tc")) {
                    continue;
                }
                List<InternalDoc.Block> content = new ArrayList<InternalDoc.Block>();
                readChildren((Element) cell, content);
                row.add(content);
            }
            rows.add(row);
        }
        return new InternalDoc.Table(rows, headerRow);
    }

    // ─── properties ──────────────────────────────────────────────────────────

    private static int headingLevelOf(Element properties) {
        String style = attributeOfChild(properties, "pStyle");
        if (style == null || !style.toLowerCase(java.util.Locale.ROOT).startsWith("heading")) {
            return 0;
        }
        try {
            return Integer.parseInt(style.substring("heading".length()).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String listStyleOf(Element properties) {
        Element numbering = firstChild(properties, "numPr");
        if (numbering == null) {
            return null;
        }
        String numberingId = attributeOfChild(numbering, "numId");
        // The generated numbering has 1 for bullets and 2 for numbers. A
        // document from elsewhere is guessed as a bullet list, which is the
        // safer wrong answer: a bullet rendered where a number belonged is
        // legible, a wrong number is misleading.
        return "2".equals(numberingId) ? "ordered" : "bullet";
    }

    private static int listLevelOf(Element properties) {
        Element numbering = firstChild(properties, "numPr");
        if (numbering == null) {
            return 0;
        }
        String level = attributeOfChild(numbering, "ilvl");
        if (level == null) {
            return 0;
        }
        try {
            return Integer.parseInt(level);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code <w:u w:val="none"/>} is not underline.
     *
     * <p>Word writes an explicit "none" when a style is being overridden, so
     * treating the element's presence as truth underlines text that is not.
     */
    private static boolean hasUnderline(Element properties) {
        Element underline = firstChild(properties, "u");
        if (underline == null) {
            return false;
        }
        String value = underline.getAttributeNS(W_NS, "val");
        return value == null || value.isEmpty() || !"none".equals(value);
    }

    private static String fontOf(Element properties) {
        Element fonts = firstChild(properties, "rFonts");
        if (fonts == null) {
            return null;
        }
        String eastAsian = fonts.getAttributeNS(W_NS, "eastAsia");
        if (eastAsian != null && !eastAsian.isEmpty()) {
            return eastAsian;
        }
        String ascii = fonts.getAttributeNS(W_NS, "ascii");
        return ascii == null || ascii.isEmpty() ? null : ascii;
    }

    /**
     * {@code <w:b/>} means bold; {@code <w:b w:val="0"/>} means explicitly not.
     */
    private static boolean hasChild(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        if (child == null) {
            return false;
        }
        String value = child.getAttributeNS(W_NS, "val");
        return !("0".equals(value) || "false".equals(value));
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (isW(child, localName)) {
                return (Element) child;
            }
        }
        return null;
    }

    private static String attributeOfChild(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        if (child == null) {
            return null;
        }
        String value = child.getAttributeNS(W_NS, "val");
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean isW(Node node, String localName) {
        return node != null && node.getNodeType() == Node.ELEMENT_NODE
                && W_NS.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName());
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isEmpty() ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String mediaTypeOf(String partName) {
        String lower = partName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    private static Map<String, String> readRelationships(OoxmlPackage document) {
        Map<String, String> targets = new LinkedHashMap<String, String>();
        String part = "word/_rels/document.xml.rels";
        if (!document.hasPart(part)) {
            return targets;
        }
        Document dom = parse(document.part(part));
        NodeList relationships = dom.getElementsByTagName("*");
        for (int index = 0; index < relationships.getLength(); index++) {
            Node node = relationships.item(index);
            if (!"Relationship".equals(node.getLocalName())) {
                continue;
            }
            Element relationship = (Element) node;
            targets.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
        }
        return targets;
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
}
