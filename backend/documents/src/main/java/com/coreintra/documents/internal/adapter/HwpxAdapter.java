package com.coreintra.documents.internal.adapter;

import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.DocumentAdapter;
import com.coreintra.documents.internal.FormatCapabilities;
import com.coreintra.documents.internal.FormatCapabilities.Feature;
import com.coreintra.documents.internal.FormatCapabilities.Support;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.internal.adapter.hwpx.HwpxPackage;
import com.coreintra.documents.internal.adapter.hwpx.HwpxSectionReader;
import com.coreintra.documents.internal.adapter.hwpx.HwpxSectionWriter;
import com.coreintra.documents.ooxml.OoxmlException;
import java.util.Locale;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;

/**
 * HWPX (OWPML, KS X 6101) — the read <em>and write</em> Korean format.
 *
 * <p>HWPX is preferred over legacy {@code .hwp} because it is a documented open
 * standard and round-trips far more reliably. Legacy {@code .hwp} is
 * {@link HwpLegacyAdapter}, and is read-only by design (see that class).
 *
 * <p>Backed by hwpxlib, verified to run on Java 8: its published jars are class
 * major 51 (Java 7), so they load on the baseline this backend targets.
 *
 * <h2>How a content control survives a format that has none</h2>
 *
 * <p>This is the decision the acceptance test turns on, so it is written down
 * here rather than left in the mapping code.
 *
 * <p>OWPML has no equivalent of a Word {@code w:sdt}. It has <b>누름틀</b>, the
 * click-here field: a named, bounded, editable region, which is the same idea
 * reached by a different route. Every content control therefore becomes
 * {@code fieldBegin type="CLICK_HERE" name="<tag>"} … value … {@code fieldEnd}.
 *
 * <p>The tag could instead have been stashed in a part of our own invention
 * inside the package. That would round-trip perfectly here and be <b>deleted
 * the first time 한글 saved the file</b>, because nothing in the document
 * references it — which is precisely the kind of fidelity claim that is true in
 * the test suite and false on the client's desk. A 누름틀 is part of the format:
 * 한글 displays it, preserves it and writes it back, so the binding survives a
 * foreign program.
 *
 * <p>A sidecar <em>is</em> still used, for a different job: content the pivot
 * model cannot express (a DOCX body, a chart) rides along as an attached
 * package part so that {@code docx → HWPX → docx} returns the original
 * formatting as well as the controls. That part is expendable by design, and
 * the reader treats its absence as normal — after a 한글 round trip the fields
 * alone carry the bindings, and the DOCX is rebuilt from the pivot model.
 */
public class HwpxAdapter implements DocumentAdapter {

    public static final String FORMAT_ID = "hwpx";

    private static final FormatCapabilities CAPABILITIES = FormatCapabilities
            .builder(FORMAT_ID, "HWPX (한글)")
            .mediaType("application/hwp+zip")
            .full(Feature.HEADINGS, Feature.RUN_EMPHASIS, Feature.LISTS, Feature.TABLES,
                    Feature.IMAGES, Feature.PAGE_BREAKS)
            .degraded(Feature.MERGED_CELLS, Feature.CONTENT_CONTROLS, Feature.APPROVAL_BLOCK,
                    Feature.HEADERS_FOOTERS, Feature.FOOTNOTES)
            .support(Feature.CHARTS, Support.PRESERVED_OPAQUE)
            .dropped(Feature.TRACKED_CHANGES, Feature.COMMENTS, Feature.COMPLEX_SCRIPT)
            .notes("Typed fields map onto 누름틀 (click-here fields), which is OWPML's own named "
                    + "editable region: the tag and the bound value survive a docx → HWPX → docx "
                    + "round trip AND survive the file being opened and saved by 한글, because "
                    + "the field is part of the format rather than a private annotation. The "
                    + "surrounding formatting may shift. A 결재란 written as a table survives as "
                    + "a table; its binding survives as a field, not as a Word structured "
                    + "document tag. Merged cells are written as unmerged cells of the same "
                    + "count. Content the model cannot express rides along as an attached "
                    + "package part so a docx round trip is lossless here, but any other program "
                    + "that opens and saves the file will discard it — after that the fields "
                    + "still carry every binding and the DOCX is rebuilt from the pivot model.")
            .build();

    private final BinaryStore binaries;

    /**
     * An adapter with no blob store.
     *
     * <p>Fine for text, tables and fields; a document containing an image is
     * refused rather than written without it. The fidelity matrix and the
     * capability declaration are static, so this constructor is also what the
     * matrix generator uses.
     */
    public HwpxAdapter() {
        this(null);
    }

    public HwpxAdapter(BinaryStore binaries) {
        this.binaries = binaries;
    }

    @Override
    public FormatCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean looksLikeThisFormat(byte[] content, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!isZip(content)) {
            return false;
        }
        if (name.endsWith(".hwpx")) {
            return true;
        }
        // HWPX and DOCX are both ZIPs and an upload may arrive with no usable
        // name, so fall back to the one entry the container standard fixes.
        return hasHwpxMimetype(content);
    }

    static boolean isZip(byte[] content) {
        return content != null && content.length > 4 && content[0] == 0x50 && content[1] == 0x4b;
    }

    private static boolean hasHwpxMimetype(byte[] content) {
        try {
            byte[] mimetype = HwpxPackage.entriesOf(content).get("mimetype");
            if (mimetype == null) {
                return false;
            }
            return new String(mimetype, com.coreintra.compat.Texts.UTF_8).trim()
                    .startsWith("application/hwp+zip");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public InternalDoc read(byte[] content) {
        if (!isZip(content)) {
            throw new OoxmlException(
                    "this file is not an HWPX package. It may be a legacy .hwp (binary), which is "
                            + "supported for import under its own extension, or a renamed file of "
                            + "another type.");
        }
        HWPXFile file = HwpxPackage.read(content);
        return new HwpxSectionReader(file, binaries, HwpxPackage.entriesOf(content))
                .read(FORMAT_ID, null);
    }

    @Override
    public byte[] write(InternalDoc document) {
        if (document == null) {
            throw new NullPointerException("document");
        }
        HWPXFile file = BlankFileMaker.make();
        HwpxSectionWriter writer = new HwpxSectionWriter(file, binaries);
        writer.write(document);
        return HwpxPackage.write(file, writer.extraParts());
    }
}
