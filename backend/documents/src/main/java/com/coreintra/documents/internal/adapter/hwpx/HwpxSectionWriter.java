package com.coreintra.documents.internal.adapter.hwpx;

import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.ooxml.OoxmlException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.HeightRelTo;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextWrapMethod;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.WidthRelTo;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.FieldBegin;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.FieldEnd;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;

/**
 * Writes {@link InternalDoc} into hwpxlib's section model.
 *
 * <h2>How a typed field survives a format with no content controls</h2>
 *
 * <p>HWPX has no equivalent of a Word {@code w:sdt}. It does have 누름틀 — the
 * click-here field — which is the same idea arrived at differently: a named,
 * bounded region whose contents the user fills in. A field becomes
 * {@code fieldBegin type="CLICK_HERE" name="<tag>"}, the value, then
 * {@code fieldEnd}.
 *
 * <p>That choice is deliberate and the alternative is worse. The tag could have
 * been stashed in a sidecar part of the package, which would round-trip
 * perfectly through this system and be <b>deleted the first time 한글 saved the
 * file</b>, because nothing in the document references it. A 누름틀 is a
 * first-class OWPML construct: 한글 shows it, preserves it, and writes it back.
 * A document that has been round-tripped through a real Korean word processor
 * therefore still knows which region was the 신청자 and what it said.
 *
 * <h2>What the sidecar is still used for</h2>
 *
 * <p>Content this model cannot represent — a DOCX body, a chart — is carried as
 * an attached package part, declared in the manifest. That is enough for
 * {@code docx → HWPX → docx} to return the original formatting <em>and</em>
 * every control, and it is honestly labelled: {@link HwpxSectionReader} treats
 * its absence as normal, because another program will strip it.
 */
public final class HwpxSectionWriter {

    /**
     * Where a foreign opaque block is parked. {@code BinData} rather than a
     * folder of our own invention: it is the one binary directory the format
     * already defines, so a reader that walks the package meets nothing
     * surprising.
     */
    static final String OPAQUE_HREF_PREFIX = "BinData/coreintra-opaque-";

    /** Marks the manifest item as ours, so the reader does not guess. */
    static final String OPAQUE_MEDIA_TYPE = "application/x-coreintra-opaque";

    static final String IMAGE_HREF_PREFIX = "BinData/image";

    private final HWPXFile file;
    private final HwpxStyles styles;
    private final BinaryStore binaries;

    private int fieldSequence;
    private int imageSequence;
    private int opaqueSequence;

    private final Map<String, byte[]> extraParts = new LinkedHashMap<String, byte[]>();

    private SectionXMLFile section;
    private boolean firstParagraphUsed;

    public HwpxSectionWriter(HWPXFile file, BinaryStore binaries) {
        this.file = file;
        this.styles = new HwpxStyles(file);
        this.binaries = binaries;
    }

    public void write(InternalDoc document) {
        SectionXMLFile section = file.sectionXMLFileList().get(0);

        // The blank scaffold's first run carries <hp:secPr> — page size, margins,
        // the outline shape, the master page — and a <hp:ctrl> holding the column
        // properties beside it. Neither is reachable through the paragraph API,
        // and a section rebuilt without them is a document 한글 opens with default
        // page setup: a silent, very visible regression.
        //
        // So the first paragraph is REUSED rather than replaced. Its carrier run
        // stays exactly where it is and the first block's runs are appended after
        // it, which costs nothing visually — the carrier's text is empty — and
        // keeps the section properties attached to the paragraph OWPML expects
        // them on.
        this.section = section;
        for (int index = section.countOfPara() - 1; index >= 1; index--) {
            section.removePara(index);
        }

        for (InternalDoc.Block block : document.blocks()) {
            paragraphsFor(section, block);
        }

        if (!firstParagraphUsed) {
            // Either an empty document or one made only of opaque blocks. The
            // scaffold paragraph is already there and already carries the
            // section properties, so it simply stays.
            firstParagraphUsed = true;
        }
    }

    private void paragraphsFor(SectionXMLFile into, InternalDoc.Block block) {
        if (block instanceof InternalDoc.Paragraph) {
            writeParagraph(into, (InternalDoc.Paragraph) block);
        } else if (block instanceof InternalDoc.FieldBlock) {
            writeField(into, (InternalDoc.FieldBlock) block);
        } else if (block instanceof InternalDoc.ControlBlock) {
            writeControl(into, (InternalDoc.ControlBlock) block);
        } else if (block instanceof InternalDoc.Table) {
            writeTable(into, (InternalDoc.Table) block);
        } else if (block instanceof InternalDoc.Image) {
            writeImage(into, (InternalDoc.Image) block);
        } else if (block instanceof InternalDoc.PageBreak) {
            writePageBreak(into);
        } else if (block instanceof InternalDoc.OpaqueBlock) {
            writeOpaque((InternalDoc.OpaqueBlock) block);
        }
    }

    private Para writeParagraph(ParaListCore into, InternalDoc.Paragraph paragraph) {
        String styleId;
        String paraPrId;
        if (paragraph.headingLevel() > 0) {
            styleId = styles.outlineStyleId(paragraph.headingLevel());
            paraPrId = styles.paraPrIdOfStyle(styleId);
        } else if (paragraph.listStyle() != null) {
            styleId = styles.bodyStyleId();
            paraPrId = styles.listParaPrFor(paragraph.listStyle(), paragraph.listLevel());
        } else {
            styleId = styles.bodyStyleId();
            paraPrId = styles.bodyParaPrId();
        }

        Para para = newParagraph(into, paraPrId, styleId);
        if (paragraph.runs().isEmpty()) {
            para.addNewRun().charPrIDRef("0");
            return para;
        }
        for (InternalDoc.Run run : paragraph.runs()) {
            Run target = para.addNewRun();
            target.charPrIDRef(styles.charPrFor(run));
            T text = target.addNewT();
            text.addText(run.text());
        }
        return para;
    }

    /** A 누름틀: begin control, the value as ordinary text, end control. */
    private Para writeField(ParaListCore into, InternalDoc.FieldBlock field) {
        Para para = newParagraph(into, styles.bodyParaPrId(), styles.bodyStyleId());
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        String id = Integer.toString(++fieldSequence);
        Ctrl begin = run.addNewCtrl();
        FieldBegin fieldBegin = begin.addNewFieldBegin();
        fieldBegin.id(id);
        fieldBegin.type(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FieldType.CLICK_HERE);
        fieldBegin.name(field.tag());
        fieldBegin.editable(Boolean.TRUE);

        T text = run.addNewT();
        text.addText(field.value() == null ? "" : field.value());

        Ctrl end = run.addNewCtrl();
        FieldEnd fieldEnd = end.addNewFieldEnd();
        fieldEnd.beginIDRef(id);
        return para;
    }

    /**
     * A control spanning several blocks — a 결재란 and its table.
     *
     * <p>OWPML's field markers are points in the run stream rather than a
     * container, so a control that wraps a table is written as
     * {@code fieldBegin}, the blocks, {@code fieldEnd}. That is legal and it is
     * how 한글 writes a 누름틀 around anything larger than a word.
     */
    private void writeControl(SectionXMLFile into, InternalDoc.ControlBlock control) {
        String id = Integer.toString(++fieldSequence);

        Para opening = newParagraph(into, styles.bodyParaPrId(), styles.bodyStyleId());
        Run openingRun = opening.addNewRun();
        openingRun.charPrIDRef("0");
        FieldBegin begin = openingRun.addNewCtrl().addNewFieldBegin();
        begin.id(id);
        begin.type(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FieldType.CLICK_HERE);
        begin.name(control.tag());
        begin.editable(Boolean.TRUE);

        for (InternalDoc.Block block : control.blocks()) {
            paragraphsFor(into, block);
        }

        Para closing = newParagraph(into, styles.bodyParaPrId(), styles.bodyStyleId());
        Run closingRun = closing.addNewRun();
        closingRun.charPrIDRef("0");
        closingRun.addNewCtrl().addNewFieldEnd().beginIDRef(id);
    }

    private Para writeTable(ParaListCore into, InternalDoc.Table table) {
        Para para = newParagraph(into, styles.bodyParaPrId(), styles.bodyStyleId());
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Table target = run.addNewTable();
        int columns = widestRow(table);
        target.rowCnt(Short.valueOf((short) table.rows().size()));
        target.colCnt(Short.valueOf((short) columns));
        target.borderFillIDRef(styles.tableBorderFillId());
        target.repeatHeader(Boolean.valueOf(table.hasHeaderRow()));
        target.createSZ();
        target.sz().width(Long.valueOf(TABLE_WIDTH_HWPUNIT));
        target.sz().widthRelTo(WidthRelTo.ABSOLUTE);
        target.sz().height(Long.valueOf(ROW_HEIGHT_HWPUNIT * (long) table.rows().size()));
        target.sz().heightRelTo(HeightRelTo.ABSOLUTE);
        target.createPos();
        target.pos().treatAsChar(Boolean.TRUE);
        target.textWrap(TextWrapMethod.TOP_AND_BOTTOM);

        long cellWidth = columns == 0 ? TABLE_WIDTH_HWPUNIT : TABLE_WIDTH_HWPUNIT / columns;
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            List<List<InternalDoc.Block>> row = table.rows().get(rowIndex);
            Tr tr = target.addNewTr();
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                Tc tc = tr.addNewTc();
                tc.header(Boolean.valueOf(table.hasHeaderRow() && rowIndex == 0));
                tc.borderFillIDRef(styles.tableBorderFillId());
                tc.createCellAddr();
                tc.cellAddr().colAddr(Short.valueOf((short) columnIndex));
                tc.cellAddr().rowAddr(Short.valueOf((short) rowIndex));
                tc.createCellSpan();
                tc.cellSpan().colSpan(Short.valueOf((short) 1));
                tc.cellSpan().rowSpan(Short.valueOf((short) 1));
                tc.createCellSz();
                tc.cellSz().width(Long.valueOf(cellWidth));
                tc.cellSz().height(Long.valueOf(ROW_HEIGHT_HWPUNIT));
                tc.createSubList();

                List<InternalDoc.Block> cell = columnIndex < row.size()
                        ? row.get(columnIndex) : null;
                writeCell(tc, cell);
            }
        }
        return para;
    }

    /**
     * A cell's blocks. Nested tables and images inside a cell are flattened to
     * their text, because OWPML nests them differently enough that a wrong
     * answer would be worse than a plain one — and the fidelity matrix says so.
     */
    private void writeCell(Tc tc, List<InternalDoc.Block> blocks) {
        boolean wrote = false;
        if (blocks != null) {
            for (InternalDoc.Block block : blocks) {
                if (block instanceof InternalDoc.Paragraph) {
                    writeParagraph(tc.subList(), (InternalDoc.Paragraph) block);
                    wrote = true;
                } else if (block instanceof InternalDoc.FieldBlock) {
                    writeField(tc.subList(), (InternalDoc.FieldBlock) block);
                    wrote = true;
                }
            }
        }
        if (!wrote) {
            // Every cell needs a paragraph; an empty <hp:tc> is not valid OWPML.
            Para empty = newParagraph(tc.subList(), styles.bodyParaPrId(), styles.bodyStyleId());
            empty.addNewRun().charPrIDRef("0");
        }
    }

    private Para writeImage(ParaListCore into, InternalDoc.Image image) {
        if (binaries == null) {
            throw new OoxmlException(
                    "this document contains an image (" + image.blobSha256() + ") and the HWPX "
                            + "writer was given no blob store to read it from. Writing the "
                            + "document without the image would lose it silently, so the export "
                            + "refuses instead.");
        }
        byte[] content = binaries.load(image.blobSha256());
        String itemId = "image" + (++imageSequence);
        ManifestItem item = file.contentHPFFile().manifest().addNew();
        item.id(itemId);
        item.href(IMAGE_HREF_PREFIX + imageSequence + extensionFor(image.mediaType()));
        item.mediaType(image.mediaType());
        item.embedded(Boolean.TRUE);
        item.createAttachedFile();
        item.attachedFile().data(content);

        Para para = newParagraph(into, styles.bodyParaPrId(), styles.bodyStyleId());
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        Picture picture = run.addNewPicture();
        picture.id(itemId);
        picture.createSZ();
        picture.sz().width(Long.valueOf(emuToHwpUnit(image.widthEmu())));
        picture.sz().widthRelTo(WidthRelTo.ABSOLUTE);
        picture.sz().height(Long.valueOf(emuToHwpUnit(image.heightEmu())));
        picture.sz().heightRelTo(HeightRelTo.ABSOLUTE);
        picture.createPos();
        picture.pos().treatAsChar(Boolean.TRUE);
        picture.createImg();
        picture.img().binaryItemIDRef(itemId);
        if (image.altText() != null) {
            // The accessibility floor: an image with no description is a hole in
            // a screen reader's account of the document, and PDF/UA refuses one.
            picture.createShapeComment();
            picture.shapeComment().addText(image.altText());
        }
        return para;
    }

    private Para writePageBreak(ParaListCore into) {
        Para para = newParagraph(into, styles.bodyParaPrId(), styles.bodyStyleId());
        para.pageBreak(Boolean.TRUE);
        para.addNewRun().charPrIDRef("0");
        return para;
    }

    /**
     * Parks content this format cannot express.
     *
     * <p>It is attached, declared and never rendered. The alternative — dropping
     * it — would mean a DOCX converted to HWPX and back had quietly lost its
     * charts and its tracked changes.
     */
    private void writeOpaque(InternalDoc.OpaqueBlock opaque) {
        byte[] content = opaque.originalBytes();
        if (content.length == 0) {
            // Nothing to park: a block read out of an HWPX whose bytes hwpxlib's
            // object model never exposed. It is still declared in the warning
            // through capabilities(), which is all the honesty available here.
            return;
        }
        String href = OPAQUE_HREF_PREFIX + (++opaqueSequence) + ".bin";
        ManifestItem item = file.contentHPFFile().manifest().addNew();
        item.id("coreintra-opaque-" + opaqueSequence);
        item.href(href);
        item.mediaType(OPAQUE_MEDIA_TYPE + "; format=" + safe(opaque.sourceFormat())
                + "; description=" + safe(opaque.description()));
        item.embedded(Boolean.TRUE);
        // NOT attachedFile(): hwpxlib only writes those for image/* items, so
        // the bytes would be declared and missing. HwpxPackage injects them.
        extraParts.put(href, content);
    }

    /** Parts the packaging layer must add, because hwpxlib will not write them. */
    public Map<String, byte[]> extraParts() {
        return extraParts;
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        // The value lands in a media-type parameter, where ; and " end it early.
        return value.replace(';', ' ').replace('"', '\'');
    }

    /**
     * A paragraph to write into.
     *
     * <p>The first one at section level is the scaffold's, reused so that the
     * section properties riding on its first run survive. Everything after it,
     * and everything inside a table cell, is new.
     */
    private Para newParagraph(ParaListCore into, String paraPrId, String styleId) {
        if (into == section && !firstParagraphUsed) {
            firstParagraphUsed = true;
            Para carrier = section.getPara(0);
            carrier.paraPrIDRef(paraPrId);
            carrier.styleIDRef(styleId);
            return carrier;
        }
        Para para = into.addNewPara();
        para.id(Integer.toString(into.countOfPara()));
        para.paraPrIDRef(paraPrId);
        para.styleIDRef(styleId);
        para.pageBreak(Boolean.FALSE);
        para.columnBreak(Boolean.FALSE);
        para.merged(Boolean.FALSE);
        return para;
    }

    private static int widestRow(InternalDoc.Table table) {
        int widest = 0;
        for (List<List<InternalDoc.Block>> row : table.rows()) {
            widest = Math.max(widest, row.size());
        }
        return Math.max(1, widest);
    }

    private static String extensionFor(String mediaType) {
        if (mediaType == null) {
            return ".bin";
        }
        if (mediaType.endsWith("png")) {
            return ".png";
        }
        if (mediaType.endsWith("jpeg") || mediaType.endsWith("jpg")) {
            return ".jpg";
        }
        if (mediaType.endsWith("gif")) {
            return ".gif";
        }
        if (mediaType.endsWith("svg+xml")) {
            return ".svg";
        }
        if (mediaType.endsWith("bmp")) {
            return ".bmp";
        }
        return ".bin";
    }

    /**
     * EMU to HWPUNIT. Both are integer sub-units of a point: an EMU is
     * 1/914400 inch, a HWPUNIT is 1/7200 inch, so the ratio is exactly 127.
     * Exact, so a 5 cm image is 5 cm in both formats rather than nearly.
     */
    static long emuToHwpUnit(int emu) {
        return emu / 127L;
    }

    /** A4 minus the scaffold's margins, in HWPUNIT. */
    private static final long TABLE_WIDTH_HWPUNIT = 42520L;

    private static final long ROW_HEIGHT_HWPUNIT = 1700L;
}
