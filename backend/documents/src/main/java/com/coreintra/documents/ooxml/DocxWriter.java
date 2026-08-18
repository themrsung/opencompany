package com.coreintra.documents.ooxml;

import com.coreintra.compat.Texts;
import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.InternalDoc;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a DOCX package from {@link InternalDoc}.
 *
 * <h2>Why this exists next to {@link OoxmlPackage}</h2>
 *
 * <p>{@link OoxmlPackage} <em>edits</em> a package that already exists, leaving
 * every part it did not touch byte-identical. That is the right tool for
 * filling in a template and the wrong one for two jobs that have no package to
 * start from:
 *
 * <ul>
 *   <li>converting a document that arrived as HWPX or mdv, where the pivot model
 *       is all there is;</li>
 *   <li>building the seeded templates, which have to be generated rather than
 *       checked in as binaries nobody can review in a diff.</li>
 * </ul>
 *
 * <p>The two paths stay distinct on purpose. Editing is surgical and lossless;
 * generating is a new document and is understood as one. Nothing routes an edit
 * through here by accident, because this class cannot be given a package.
 *
 * <h2>Deterministic output</h2>
 *
 * <p>Zip entry timestamps are fixed at zero and parts are written in a fixed
 * order, so the same model produces the same bytes. Seeded templates are
 * content-addressed in the blob store, and a template whose hash changed on
 * every boot would install a new "version" on every boot.
 *
 * <h2>The subset</h2>
 *
 * <p>Paragraphs, runs with bold/italic/underline/strikethrough, headings,
 * bulleted and numbered lists, tables, inline images, page breaks and content
 * controls — the same subset the editor supports and the HWPX serialiser
 * writes, because §6.6 asks for one editing surface with two serialisers rather
 * than two editors that drift.
 */
public final class DocxWriter {

    private static final String W_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WP_NS =
            "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    private static final String CT_NS =
            "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String PKG_REL_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";

    /**
     * §6.9: Pretendard leads, because its Korean and Latin are designed
     * together and a mixed 한/영 line does not ransom-note. The fallback is
     * named too, so a box without Pretendard degrades to a Korean-capable face
     * rather than to whatever fontconfig picks.
     */
    private static final String DEFAULT_FONT = "Pretendard";
    private static final String DEFAULT_FALLBACK_FONT = "Noto Sans CJK KR";

    private static final int MAX_HEADING_LEVEL = 6;

    private final BinaryStore binaries;
    private final List<Media> media = new ArrayList<Media>();
    private final Set<String> extensions = new LinkedHashSet<String>();

    public DocxWriter() {
        this(null);
    }

    public DocxWriter(BinaryStore binaries) {
        this.binaries = binaries;
    }

    /** Serialises a document. Not reusable: one writer, one document. */
    public byte[] write(InternalDoc document) {
        if (document == null) {
            throw new NullPointerException("document");
        }
        StringBuilder body = new StringBuilder();
        for (InternalDoc.Block block : document.blocks()) {
            appendBlock(body, block);
        }
        if (body.length() == 0) {
            // Word refuses a body with no block-level content.
            body.append("<w:p/>");
        }

        Map<String, byte[]> parts = new LinkedHashMap<String, byte[]>();
        parts.put(OoxmlPackage.CONTENT_TYPES_PART, utf8(contentTypes()));
        parts.put("_rels/.rels", utf8(packageRelationships()));
        parts.put(OoxmlPackage.DOCUMENT_PART, utf8(documentPart(body.toString())));
        parts.put("word/_rels/document.xml.rels", utf8(documentRelationships()));
        parts.put("word/styles.xml", utf8(styles()));
        parts.put("word/numbering.xml", utf8(numbering()));
        for (Media item : media) {
            parts.put("word/" + item.target, item.content);
        }
        return zip(parts);
    }

    // ─── body ────────────────────────────────────────────────────────────────

    private void appendBlock(StringBuilder out, InternalDoc.Block block) {
        if (block instanceof InternalDoc.Paragraph) {
            appendParagraph(out, (InternalDoc.Paragraph) block);
        } else if (block instanceof InternalDoc.FieldBlock) {
            appendField(out, (InternalDoc.FieldBlock) block);
        } else if (block instanceof InternalDoc.ControlBlock) {
            appendControl(out, (InternalDoc.ControlBlock) block);
        } else if (block instanceof InternalDoc.Table) {
            appendTable(out, (InternalDoc.Table) block);
        } else if (block instanceof InternalDoc.Image) {
            appendImage(out, (InternalDoc.Image) block);
        } else if (block instanceof InternalDoc.PageBreak) {
            out.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
        } else if (block instanceof InternalDoc.OpaqueBlock) {
            appendOpaque(out, (InternalDoc.OpaqueBlock) block);
        }
    }

    private void appendParagraph(StringBuilder out, InternalDoc.Paragraph paragraph) {
        out.append("<w:p>");
        appendParagraphProperties(out, paragraph);
        for (InternalDoc.Run run : paragraph.runs()) {
            appendRun(out, run);
        }
        out.append("</w:p>");
    }

    private void appendParagraphProperties(StringBuilder out, InternalDoc.Paragraph paragraph) {
        boolean heading = paragraph.headingLevel() > 0;
        boolean list = paragraph.listStyle() != null;
        if (!heading && !list) {
            return;
        }
        out.append("<w:pPr>");
        if (heading) {
            int level = Math.min(MAX_HEADING_LEVEL, paragraph.headingLevel());
            out.append("<w:pStyle w:val=\"Heading").append(level).append("\"/>");
        }
        if (list) {
            int numberingId = "ordered".equals(paragraph.listStyle()) ? 2 : 1;
            out.append("<w:pStyle w:val=\"ListParagraph\"/>")
               .append("<w:numPr><w:ilvl w:val=\"")
               .append(Math.max(0, Math.min(8, paragraph.listLevel())))
               .append("\"/><w:numId w:val=\"").append(numberingId).append("\"/></w:numPr>");
        }
        out.append("</w:pPr>");
    }

    private void appendRun(StringBuilder out, InternalDoc.Run run) {
        out.append("<w:r>");
        boolean styled = run.bold() || run.italic() || run.underline() || run.strike()
                || run.fontFamily() != null;
        if (styled) {
            out.append("<w:rPr>");
            if (run.fontFamily() != null) {
                String font = escape(run.fontFamily());
                // eastAsia as well as ascii: a Korean run whose rFonts names only
                // ascii is rendered by Word with the theme's East Asian face, and
                // the document silently stops looking like the one authored.
                out.append("<w:rFonts w:ascii=\"").append(font)
                   .append("\" w:hAnsi=\"").append(font)
                   .append("\" w:eastAsia=\"").append(font).append("\"/>");
            }
            if (run.bold()) {
                out.append("<w:b/><w:bCs/>");
            }
            if (run.italic()) {
                out.append("<w:i/><w:iCs/>");
            }
            if (run.underline()) {
                out.append("<w:u w:val=\"single\"/>");
            }
            if (run.strike()) {
                out.append("<w:strike/>");
            }
            out.append("</w:rPr>");
        }
        // xml:space matters: without it a value of " " or a trailing space in a
        // field is collapsed away by the consumer, and a filled-in form loses
        // its spacing.
        out.append("<w:t xml:space=\"preserve\">").append(escape(run.text())).append("</w:t>");
        out.append("</w:r>");
    }

    private void appendField(StringBuilder out, InternalDoc.FieldBlock field) {
        out.append("<w:sdt>");
        appendControlProperties(out, field.tag(), null);
        out.append("<w:sdtContent><w:p><w:r><w:t xml:space=\"preserve\">")
           .append(escape(field.value() == null ? "" : field.value()))
           .append("</w:t></w:r></w:p></w:sdtContent></w:sdt>");
    }

    private void appendControl(StringBuilder out, InternalDoc.ControlBlock control) {
        out.append("<w:sdt>");
        appendControlProperties(out, control.tag(), control.alias());
        out.append("<w:sdtContent>");
        boolean wrote = false;
        for (InternalDoc.Block block : control.blocks()) {
            appendBlock(out, block);
            wrote = true;
        }
        if (!wrote) {
            out.append("<w:p/>");
        }
        out.append("</w:sdtContent></w:sdt>");
    }

    private void appendControlProperties(StringBuilder out, String tag, String alias) {
        out.append("<w:sdtPr>");
        if (alias != null) {
            out.append("<w:alias w:val=\"").append(escape(alias)).append("\"/>");
        }
        out.append("<w:tag w:val=\"").append(escape(tag)).append("\"/>")
           .append("<w:id w:val=\"").append(stableId(tag)).append("\"/>")
           .append("</w:sdtPr>");
    }

    /**
     * A control id derived from the tag.
     *
     * <p>Word wants a signed 32-bit id and does not care what it is, but a
     * random one would make the generated bytes differ run to run, which breaks
     * the content-addressed install of the seeded templates.
     */
    private static int stableId(String tag) {
        int hash = tag == null ? 0 : tag.hashCode();
        return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash);
    }

    private void appendTable(StringBuilder out, InternalDoc.Table table) {
        int columns = 1;
        for (List<List<InternalDoc.Block>> row : table.rows()) {
            columns = Math.max(columns, row.size());
        }
        // A4 text width in twentieths of a point, split evenly. A table with no
        // explicit widths is laid out differently by Word and LibreOffice, which
        // is exactly the kind of drift that makes an export look wrong.
        int totalWidth = 9026;
        int columnWidth = totalWidth / columns;

        out.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append(totalWidth).append("\" w:type=\"dxa\"/>")
           .append("<w:tblBorders>");
        String[] edges = {"top", "left", "bottom", "right", "insideH", "insideV"};
        for (int index = 0; index < edges.length; index++) {
            out.append("<w:").append(edges[index])
               .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>");
        }
        out.append("</w:tblBorders></w:tblPr><w:tblGrid>");
        for (int index = 0; index < columns; index++) {
            out.append("<w:gridCol w:w=\"").append(columnWidth).append("\"/>");
        }
        out.append("</w:tblGrid>");

        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            List<List<InternalDoc.Block>> row = table.rows().get(rowIndex);
            out.append("<w:tr>");
            if (table.hasHeaderRow() && rowIndex == 0) {
                // Repeats the header on every page. §6.10 notes pagination
                // already does this for mdv; a DOCX has to be told.
                out.append("<w:trPr><w:tblHeader/></w:trPr>");
            }
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                out.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(columnWidth)
                   .append("\" w:type=\"dxa\"/></w:tcPr>");
                List<InternalDoc.Block> cell = columnIndex < row.size()
                        ? row.get(columnIndex) : null;
                boolean wrote = false;
                if (cell != null) {
                    for (InternalDoc.Block block : cell) {
                        appendBlock(out, block);
                        wrote = true;
                    }
                }
                if (!wrote) {
                    // A cell with no paragraph is not a valid cell.
                    out.append("<w:p/>");
                }
                out.append("</w:tc>");
            }
            out.append("</w:tr>");
        }
        out.append("</w:tbl>");
        // Word merges two adjacent tables into one unless something separates
        // them, which silently welds a 결재란 onto the table below it.
        out.append("<w:p/>");
    }

    private void appendImage(StringBuilder out, InternalDoc.Image image) {
        if (binaries == null) {
            throw new OoxmlException(
                    "this document contains an image (" + image.blobSha256() + ") and the DOCX "
                            + "writer was given no blob store to read it from. Writing the "
                            + "document without the image would lose it silently, so it refuses "
                            + "instead.");
        }
        byte[] content = binaries.load(image.blobSha256());
        String extension = extensionFor(image.mediaType());
        extensions.add(extension);
        int number = media.size() + 1;
        String target = "media/image" + number + "." + extension;
        String relationshipId = "rIdImage" + number;
        media.add(new Media(target, relationshipId, content, image.mediaType()));

        long widthEmu = image.widthEmu() > 0 ? image.widthEmu() : 2857500L;
        long heightEmu = image.heightEmu() > 0 ? image.heightEmu() : 2857500L;
        String description = image.altText() == null ? "" : escape(image.altText());

        out.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
           .append("<wp:extent cx=\"").append(widthEmu).append("\" cy=\"").append(heightEmu)
           .append("\"/><wp:docPr id=\"").append(number).append("\" name=\"Picture ").append(number)
           .append("\" descr=\"").append(description).append("\"/>")
           .append("<a:graphic xmlns:a=\"").append(A_NS).append("\">")
           .append("<a:graphicData uri=\"").append(PIC_NS).append("\">")
           .append("<pic:pic xmlns:pic=\"").append(PIC_NS).append("\">")
           .append("<pic:nvPicPr><pic:cNvPr id=\"").append(number).append("\" name=\"Picture ")
           .append(number).append("\" descr=\"").append(description).append("\"/>")
           .append("<pic:cNvPicPr/></pic:nvPicPr>")
           .append("<pic:blipFill><a:blip r:embed=\"").append(relationshipId)
           .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
           .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(widthEmu)
           .append("\" cy=\"").append(heightEmu).append("\"/></a:xfrm>")
           .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
           .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
    }

    /**
     * Opaque content, as a visible marker rather than as silence.
     *
     * <p>A DOCX body carried from a DOCX source is replayed by
     * {@code DocxAdapter} before this writer is ever reached, so anything
     * arriving here came from a format whose bytes cannot be replanted in an
     * OOXML package — an HWPX equation, say. Writing nothing would be the
     * silent drop the pivot model exists to prevent, so the document says what
     * was there, in both languages, and the export warning says it too.
     */
    private void appendOpaque(StringBuilder out, InternalDoc.OpaqueBlock opaque) {
        String description = opaque.description() == null ? "unknown" : opaque.description();
        out.append("<w:p><w:pPr><w:pStyle w:val=\"OpaqueMarker\"/></w:pPr><w:r><w:rPr><w:i/>")
           .append("<w:color w:val=\"7F7F7F\"/></w:rPr><w:t xml:space=\"preserve\">")
           .append(escape("[변환할 수 없는 내용: " + description
                   + " / content that could not be converted: " + description + "]"))
           .append("</w:t></w:r></w:p>");
    }

    // ─── parts ───────────────────────────────────────────────────────────────

    private String contentTypes() {
        StringBuilder out = new StringBuilder();
        out.append(XML_DECLARATION)
           .append("<Types xmlns=\"").append(CT_NS).append("\">")
           .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-")
           .append("package.relationships+xml\"/>")
           .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        for (String extension : extensions) {
            out.append("<Default Extension=\"").append(extension).append("\" ContentType=\"")
               .append(contentTypeForExtension(extension)).append("\"/>");
        }
        out.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.")
           .append("openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
           .append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.")
           .append("openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
           .append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.")
           .append("openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>")
           .append("</Types>");
        return out.toString();
    }

    private String packageRelationships() {
        return XML_DECLARATION
                + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + R_NS + "/officeDocument\" "
                + "Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private String documentRelationships() {
        StringBuilder out = new StringBuilder();
        out.append(XML_DECLARATION).append("<Relationships xmlns=\"").append(PKG_REL_NS).append("\">")
           .append("<Relationship Id=\"rId1\" Type=\"").append(R_NS)
           .append("/styles\" Target=\"styles.xml\"/>")
           .append("<Relationship Id=\"rId2\" Type=\"").append(R_NS)
           .append("/numbering\" Target=\"numbering.xml\"/>");
        for (Media item : media) {
            out.append("<Relationship Id=\"").append(item.relationshipId).append("\" Type=\"")
               .append(R_NS).append("/image\" Target=\"").append(item.target).append("\"/>");
        }
        out.append("</Relationships>");
        return out.toString();
    }

    private String documentPart(String body) {
        return XML_DECLARATION
                + "<w:document xmlns:w=\"" + W_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:wp=\""
                + WP_NS + "\" xmlns:a=\"" + A_NS + "\" xmlns:pic=\"" + PIC_NS + "\">"
                + "<w:body>" + body
                // A4 portrait with 20 mm margins, stated explicitly so the page
                // is the same size wherever the document is opened.
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" "
                + "w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>";
    }

    private String styles() {
        StringBuilder out = new StringBuilder();
        out.append(XML_DECLARATION).append("<w:styles xmlns:w=\"").append(W_NS).append("\">")
           .append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"").append(DEFAULT_FONT)
           .append("\" w:hAnsi=\"").append(DEFAULT_FONT).append("\" w:eastAsia=\"")
           .append(DEFAULT_FONT).append("\" w:cs=\"").append(DEFAULT_FALLBACK_FONT)
           .append("\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/><w:lang w:val=\"en-GB\" ")
           .append("w:eastAsia=\"ko-KR\"/></w:rPr></w:rPrDefault></w:docDefaults>")
           .append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">")
           .append("<w:name w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" ")
           .append("w:lineRule=\"auto\"/></w:pPr></w:style>");
        for (int level = 1; level <= MAX_HEADING_LEVEL; level++) {
            int size = 32 - (level - 1) * 2;
            out.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level)
               .append("\"><w:name w:val=\"heading ").append(level)
               .append("\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"")
               .append(level - 1).append("\"/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr>")
               .append("<w:rPr><w:b/><w:sz w:val=\"").append(size).append("\"/><w:szCs w:val=\"")
               .append(size).append("\"/></w:rPr></w:style>");
        }
        out.append("<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\">")
           .append("<w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>")
           .append("<w:pPr><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>")
           .append("<w:style w:type=\"paragraph\" w:styleId=\"OpaqueMarker\">")
           .append("<w:name w:val=\"Unconverted content\"/><w:basedOn w:val=\"Normal\"/></w:style>")
           .append("</w:styles>");
        return out.toString();
    }

    /**
     * One bulleted and one numbered definition, nine levels each.
     *
     * <p>The numbered levels alternate decimal, 가나다 and parenthesised decimal,
     * which is the Korean document convention and matches what the HWPX
     * serialiser gets from 한글's own built-in numbering. A list that renumbers
     * itself differently in each format is a fidelity bug users notice
     * immediately.
     */
    private String numbering() {
        StringBuilder out = new StringBuilder();
        out.append(XML_DECLARATION).append("<w:numbering xmlns:w=\"").append(W_NS).append("\">");
        out.append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        for (int level = 0; level < 9; level++) {
            out.append("<w:lvl w:ilvl=\"").append(level).append("\"><w:start w:val=\"1\"/>")
               .append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"").append(bulletFor(level))
               .append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"")
               .append(720 * (level + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        out.append("</w:abstractNum>");
        out.append("<w:abstractNum w:abstractNumId=\"2\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        for (int level = 0; level < 9; level++) {
            String format = numberFormatFor(level);
            String text = "%" + (level + 1) + (level % 3 == 2 ? ")" : ".");
            out.append("<w:lvl w:ilvl=\"").append(level).append("\"><w:start w:val=\"1\"/>")
               .append("<w:numFmt w:val=\"").append(format).append("\"/><w:lvlText w:val=\"")
               .append(text).append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"")
               .append(720 * (level + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        out.append("</w:abstractNum>")
           .append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num>")
           .append("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num>")
           .append("</w:numbering>");
        return out.toString();
    }

    private static String bulletFor(int level) {
        int position = level % 3;
        if (position == 0) {
            return "•";
        }
        if (position == 1) {
            return "○";
        }
        return "▪";
    }

    private static String numberFormatFor(int level) {
        int position = level % 3;
        if (position == 1) {
            return "koreanCounting";
        }
        return "decimal";
    }

    // ─── packaging ───────────────────────────────────────────────────────────

    private static byte[] zip(Map<String, byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                ZipEntry entry = new ZipEntry(part.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(part.getValue());
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException e) {
            throw new OoxmlException("failed to write the generated DOCX package", e);
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
                // A byte-array-backed stream cannot meaningfully fail to close.
            }
        }
        return out.toByteArray();
    }

    private static byte[] utf8(String xml) {
        return xml.getBytes(Texts.UTF_8);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '&') {
                out.append("&amp;");
            } else if (character == '<') {
                out.append("&lt;");
            } else if (character == '>') {
                out.append("&gt;");
            } else if (character == '"') {
                out.append("&quot;");
            } else if (character == '\'') {
                out.append("&apos;");
            } else if (character == '\t' || character == '\n' || character == '\r') {
                out.append(character);
            } else if (character < 0x20) {
                // Not representable in XML 1.0 at all. Dropping the character is
                // the only legal option; keeping it makes the file unopenable.
                continue;
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }

    private static String extensionFor(String mediaType) {
        if (mediaType == null) {
            return "png";
        }
        if (mediaType.endsWith("jpeg") || mediaType.endsWith("jpg")) {
            return "jpeg";
        }
        if (mediaType.endsWith("gif")) {
            return "gif";
        }
        if (mediaType.endsWith("svg+xml")) {
            return "svg";
        }
        if (mediaType.endsWith("bmp")) {
            return "bmp";
        }
        return "png";
    }

    private static String contentTypeForExtension(String extension) {
        if ("jpeg".equals(extension)) {
            return "image/jpeg";
        }
        if ("gif".equals(extension)) {
            return "image/gif";
        }
        if ("svg".equals(extension)) {
            return "image/svg+xml";
        }
        if ("bmp".equals(extension)) {
            return "image/bmp";
        }
        return "image/png";
    }

    private static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private static final class Media {
        private final String target;
        private final String relationshipId;
        private final byte[] content;
        private final String mediaType;

        Media(String target, String relationshipId, byte[] content, String mediaType) {
            this.target = target;
            this.relationshipId = relationshipId;
            this.content = content;
            this.mediaType = mediaType;
        }

        String mediaType() {
            return mediaType;
        }
    }
}
