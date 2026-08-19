package com.coreintra.documents.internal.adapter.hwpx;

import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.InternalDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ParaHeadingType;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.CtrlItem;
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
 * Reads hwpxlib's section model into {@link InternalDoc}.
 *
 * <h2>Reading a 누름틀 back</h2>
 *
 * <p>A field is not a container in OWPML — it is a pair of markers in the run
 * stream, {@code fieldBegin} and {@code fieldEnd}, with ordinary text between
 * them. So the reader is a small state machine rather than a tree walk: text
 * met while a field is open belongs to that field, text met outside one is
 * body text. That is also why a document another program has edited still
 * reads correctly, provided it kept the markers — which 한글 does.
 *
 * <h2>What becomes opaque</h2>
 *
 * <p>Equations, OLE objects, charts, text art and drawing shapes have no place
 * in the pivot model. Rather than dropping them, each is recorded as an
 * {@link InternalDoc.OpaqueBlock} naming what it was, so the export warning can
 * say "this document contains an equation" instead of a document quietly
 * losing one. Their bytes are not recoverable through hwpxlib's object model,
 * so the block carries a description and no content — the honest position, and
 * the reason the fidelity matrix marks them dropped rather than preserved for
 * an HWPX source.
 */
public final class HwpxSectionReader {

    private final HWPXFile file;
    private final BinaryStore binaries;
    private final Map<String, byte[]> archiveEntries;
    private final Map<String, CharPr> charProperties = new LinkedHashMap<String, CharPr>();
    private final Map<String, ParaPr> paraProperties = new LinkedHashMap<String, ParaPr>();
    private final Map<String, String> fieldValues = new LinkedHashMap<String, String>();

    /** The control currently being collected, if any. See {@link #sink}. */
    private String openTag;
    private List<InternalDoc.Block> openBlocks;
    private final StringBuilder openText = new StringBuilder();

    public HwpxSectionReader(HWPXFile file, BinaryStore binaries) {
        this(file, binaries, null);
    }

    /**
     * @param archiveEntries the raw package entries, or null. Needed because the
     *        parked opaque parts are written by {@link HwpxPackage} rather than
     *        by hwpxlib, which only emits attached files for images, so they
     *        cannot be read back through the object model either
     */
    public HwpxSectionReader(HWPXFile file, BinaryStore binaries,
            Map<String, byte[]> archiveEntries) {
        this.file = file;
        this.binaries = binaries;
        this.archiveEntries = archiveEntries;
        indexProperties();
    }

    public InternalDoc read(String formatId, String originalBlobSha256) {
        List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
        ObjectList<SectionXMLFile> sections = file.sectionXMLFileList();
        for (int index = 0; index < sections.count(); index++) {
            readParagraphs(sections.get(index), blocks);
        }
        if (openTag != null) {
            // A begin with no end: the document is malformed, but its value is
            // still there and losing it would be the worse failure.
            closeControl(blocks);
        }
        readAttachedOpaqueBlocks(blocks);
        return new InternalDoc(blocks, fieldValues, formatId, originalBlobSha256);
    }

    private void readParagraphs(ParaListCore paragraphs, List<InternalDoc.Block> into) {
        for (int index = 0; index < paragraphs.countOfPara(); index++) {
            readParagraph(paragraphs.getPara(index), into);
        }
    }

    private void readParagraph(Para para, List<InternalDoc.Block> into) {
        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();

        // OWPML's pageBreak means "break BEFORE this paragraph", so the pivot
        // model's explicit break block belongs in front of the content, not
        // after it. Getting this backwards moves every page boundary by one
        // paragraph on the way back out.
        if (Boolean.TRUE.equals(para.pageBreak())) {
            sink(into).add(new InternalDoc.PageBreak());
        }

        for (int runIndex = 0; runIndex < para.countOfRun(); runIndex++) {
            Run run = para.getRun(runIndex);
            String charPrId = run.charPrIDRef();
            for (int itemIndex = 0; itemIndex < run.countOfRunItem(); itemIndex++) {
                RunItem item = run.getRunItem(itemIndex);
                if (item instanceof T) {
                    String text = textOf((T) item);
                    if (openTag != null) {
                        openText.append(text);
                    } else if (text.length() > 0) {
                        runs.add(runFrom(text, charPrId));
                    }
                } else if (item instanceof Ctrl) {
                    Ctrl control = (Ctrl) item;
                    for (int ctrlIndex = 0; ctrlIndex < control.countOfCtrlItems(); ctrlIndex++) {
                        CtrlItem ctrlItem = control.getCtrlItem(ctrlIndex);
                        if (ctrlItem instanceof FieldBegin) {
                            flushParagraph(runs, para, into);
                            openControl(((FieldBegin) ctrlItem).name());
                        } else if (ctrlItem instanceof FieldEnd && openTag != null) {
                            closeControl(into);
                        }
                    }
                } else if (item instanceof Table) {
                    flushParagraph(runs, para, into);
                    sink(into).add(readTable((Table) item));
                } else if (item instanceof Picture) {
                    flushParagraph(runs, para, into);
                    sink(into).add(readPicture((Picture) item));
                } else if (item instanceof kr.dogfoot.hwpxlib.object.content.section_xml
                        .paragraph.object.shapeobject.ShapeObject) {
                    flushParagraph(runs, para, into);
                    sink(into).add(new InternalDoc.OpaqueBlock("hwpx",
                            describe(item.getClass().getSimpleName()), null));
                }
            }
        }

        flushParagraph(runs, para, into);
    }

    /**
     * Where a block goes: into an open control if one is being collected,
     * otherwise straight into the document.
     *
     * <p>A 누름틀 is a pair of markers rather than a container, so "inside the
     * control" is a reading state, not a nesting level.
     */
    private List<InternalDoc.Block> sink(List<InternalDoc.Block> into) {
        return openTag == null ? into : openBlocks;
    }

    private void openControl(String tag) {
        // A field opening while another is open is malformed. Closing the first
        // keeps both, which is better than discarding either.
        if (openTag != null) {
            closeControl(new ArrayList<InternalDoc.Block>());
        }
        openTag = tag;
        openText.setLength(0);
        openBlocks = new ArrayList<InternalDoc.Block>();
    }

    /**
     * Ends a control, choosing the block type from what it turned out to hold.
     *
     * <p>A control holding nothing but its own text is a typed field and reads
     * back as one; a control holding a table — a 결재란 — has to keep its
     * structure, so it reads back as a {@link InternalDoc.ControlBlock}. The
     * distinction is made from the content rather than from the tag, because
     * the tag belongs to the client's schema and not to us.
     */
    private void closeControl(List<InternalDoc.Block> into) {
        String tag = openTag;
        List<InternalDoc.Block> collected = openBlocks;
        String text = openText.toString();
        openTag = null;
        openBlocks = null;
        openText.setLength(0);
        if (tag == null) {
            return;
        }
        if (collected == null || collected.isEmpty()) {
            into.add(new InternalDoc.FieldBlock(tag, text));
            fieldValues.put(tag, text);
            return;
        }
        into.add(new InternalDoc.ControlBlock(tag, null, collected));
        fieldValues.put(tag, text);
    }

    private void flushParagraph(List<InternalDoc.Run> runs, Para para,
            List<InternalDoc.Block> into) {
        if (runs.isEmpty()) {
            return;
        }
        sink(into).add(new InternalDoc.Paragraph(new ArrayList<InternalDoc.Run>(runs),
                headingLevelOf(para), listStyleOf(para), listLevelOf(para)));
        runs.clear();
    }

    private InternalDoc.Table readTable(Table table) {
        List<List<List<InternalDoc.Block>>> rows =
                new ArrayList<List<List<InternalDoc.Block>>>();
        boolean headerRow = false;
        for (int rowIndex = 0; rowIndex < table.countOfTr(); rowIndex++) {
            Tr tr = table.getTr(rowIndex);
            List<List<InternalDoc.Block>> row = new ArrayList<List<InternalDoc.Block>>();
            for (int cellIndex = 0; cellIndex < tr.countOfTc(); cellIndex++) {
                Tc tc = tr.getTc(cellIndex);
                if (rowIndex == 0 && Boolean.TRUE.equals(tc.header())) {
                    headerRow = true;
                }
                List<InternalDoc.Block> cell = new ArrayList<InternalDoc.Block>();
                if (tc.subList() != null) {
                    readParagraphs(tc.subList(), cell);
                }
                row.add(cell);
            }
            rows.add(row);
        }
        return new InternalDoc.Table(rows, headerRow);
    }

    /**
     * A picture, with its bytes lifted out of the package and into the blob
     * store so the pivot model can refer to them by hash like every other image.
     */
    private InternalDoc.Block readPicture(Picture picture) {
        String reference = picture.img() == null ? null : picture.img().binaryItemIDRef();
        ManifestItem item = reference == null ? null : manifestItem(reference);
        if (item == null || !item.hasAttachedFile() || binaries == null) {
            return new InternalDoc.OpaqueBlock("hwpx",
                    "image whose binary data is not in the package", null);
        }
        byte[] content = item.attachedFile().data();
        String sha256 = binaries.store(content, item.mediaType());
        int widthEmu = picture.sz() == null || picture.sz().width() == null
                ? 0 : hwpUnitToEmu(picture.sz().width().longValue());
        int heightEmu = picture.sz() == null || picture.sz().height() == null
                ? 0 : hwpUnitToEmu(picture.sz().height().longValue());
        String altText = picture.shapeComment() == null ? null : picture.shapeComment().text();
        return new InternalDoc.Image(sha256, item.mediaType(), altText, widthEmu, heightEmu);
    }

    /**
     * Content parked by {@link HwpxSectionWriter}, if it is still there.
     *
     * <p>Its absence is not an error. Nothing in the document references these
     * parts, so any other program that opens and saves the file is entitled to
     * drop them — and 한글 will. The fields carry the data that has to survive
     * that; this only restores the extra fidelity when the file has come
     * straight back to us.
     */
    private void readAttachedOpaqueBlocks(List<InternalDoc.Block> into) {
        ObjectList<ManifestItem> manifest = file.contentHPFFile().manifest();
        if (manifest == null) {
            return;
        }
        for (int index = 0; index < manifest.count(); index++) {
            ManifestItem item = manifest.get(index);
            String mediaType = item.mediaType();
            if (mediaType == null
                    || !mediaType.startsWith(HwpxSectionWriter.OPAQUE_MEDIA_TYPE)) {
                continue;
            }
            byte[] content = archiveEntries == null ? null : archiveEntries.get(item.href());
            if (content == null) {
                // Declared but gone: the manifest survived a foreign save and
                // the part did not. Recorded so the user is still told what the
                // document used to carry.
                into.add(new InternalDoc.OpaqueBlock(parameter(mediaType, "format"),
                        parameter(mediaType, "description"), null));
                continue;
            }
            into.add(new InternalDoc.OpaqueBlock(
                    parameter(mediaType, "format"),
                    parameter(mediaType, "description"),
                    content));
        }
    }

    private static String parameter(String mediaType, String name) {
        for (String part : mediaType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return "unknown";
    }

    private ManifestItem manifestItem(String id) {
        ObjectList<ManifestItem> manifest = file.contentHPFFile().manifest();
        if (manifest == null) {
            return null;
        }
        for (int index = 0; index < manifest.count(); index++) {
            if (id.equals(manifest.get(index).id())) {
                return manifest.get(index);
            }
        }
        return null;
    }

    private InternalDoc.Run runFrom(String text, String charPrId) {
        CharPr properties = charPrId == null ? null : charProperties.get(charPrId);
        if (properties == null) {
            return InternalDoc.Run.plain(text);
        }
        boolean underline = properties.underline() != null
                && properties.underline().type() != null
                && !kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.UnderlineType.NONE
                        .equals(properties.underline().type());
        boolean strike = properties.strikeout() != null
                && properties.strikeout().shape() != null
                && !kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.NONE
                        .equals(properties.strikeout().shape());
        return new InternalDoc.Run(text, properties.bold() != null, properties.italic() != null,
                underline, strike, null);
    }

    /**
     * The outline level a paragraph's style implies.
     *
     * <p>Read from the paragraph property's {@code heading} rather than from the
     * style name, because a client who renames 개요 1 has not stopped it being a
     * level-one heading.
     */
    private int headingLevelOf(Para para) {
        ParaPr properties = paraProperties.get(para.paraPrIDRef());
        if (properties == null || properties.heading() == null
                || !ParaHeadingType.OUTLINE.equals(properties.heading().type())) {
            return 0;
        }
        Byte level = properties.heading().level();
        return level == null ? 1 : level.intValue() + 1;
    }

    private String listStyleOf(Para para) {
        ParaPr properties = paraProperties.get(para.paraPrIDRef());
        if (properties == null || properties.heading() == null) {
            return null;
        }
        if (ParaHeadingType.NUMBER.equals(properties.heading().type())) {
            return "ordered";
        }
        if (ParaHeadingType.BULLET.equals(properties.heading().type())) {
            return "bullet";
        }
        return null;
    }

    private int listLevelOf(Para para) {
        ParaPr properties = paraProperties.get(para.paraPrIDRef());
        if (properties == null || properties.heading() == null
                || properties.heading().level() == null) {
            return 0;
        }
        return properties.heading().level().intValue();
    }

    private void indexProperties() {
        if (file.headerXMLFile() == null || file.headerXMLFile().refList() == null) {
            return;
        }
        ObjectList<CharPr> chars = file.headerXMLFile().refList().charProperties();
        if (chars != null) {
            for (int index = 0; index < chars.count(); index++) {
                charProperties.put(chars.get(index).id(), chars.get(index));
            }
        }
        ObjectList<ParaPr> paras = file.headerXMLFile().refList().paraProperties();
        if (paras != null) {
            for (int index = 0; index < paras.count(); index++) {
                paraProperties.put(paras.get(index).id(), paras.get(index));
            }
        }
    }

    private static String textOf(T text) {
        String only = text.onlyText();
        return only == null ? "" : only;
    }

    /** A name a user would recognise in an export warning. */
    private static String describe(String className) {
        if ("Equation".equals(className)) {
            return "equation";
        }
        if ("Chart".equals(className)) {
            return "chart";
        }
        if ("OLE".equals(className)) {
            return "embedded object";
        }
        if ("TextArt".equals(className)) {
            return "text art";
        }
        if ("Container".equals(className)) {
            return "grouped drawing";
        }
        return "drawing object (" + className + ")";
    }

    static int hwpUnitToEmu(long hwpUnit) {
        long emu = hwpUnit * 127L;
        return emu > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) emu;
    }
}
