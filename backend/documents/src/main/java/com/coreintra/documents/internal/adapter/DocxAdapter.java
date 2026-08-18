package com.coreintra.documents.internal.adapter;

import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.DocumentAdapter;
import com.coreintra.documents.internal.FormatCapabilities;
import com.coreintra.documents.internal.FormatCapabilities.Feature;
import com.coreintra.documents.internal.FormatCapabilities.Support;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.ooxml.ContentControls;
import com.coreintra.documents.ooxml.DocxReader;
import com.coreintra.documents.ooxml.DocxWriter;
import com.coreintra.documents.ooxml.OoxmlPackage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DOCX — the canonical format.
 *
 * <p>Every template and every document is stored as DOCX. This adapter exists
 * for conversion <em>to and from other formats</em>; editing a DOCX in place
 * does not go through the pivot model at all, because
 * {@link OoxmlPackage} preserves the original bytes and the pivot model
 * necessarily does not.
 *
 * <p>That distinction is the reason the round trip is byte-identical: a DOCX
 * edit touches one part of the real package, while a conversion is understood
 * by everyone involved to be a new document.
 */
public class DocxAdapter implements DocumentAdapter {

    public static final String FORMAT_ID = "docx";

    private static final FormatCapabilities CAPABILITIES = FormatCapabilities
            .builder(FORMAT_ID, "DOCX (Word)")
            .mediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            .full(Feature.HEADINGS, Feature.RUN_EMPHASIS, Feature.LISTS, Feature.TABLES,
                    Feature.MERGED_CELLS, Feature.IMAGES, Feature.PAGE_BREAKS,
                    Feature.CONTENT_CONTROLS, Feature.APPROVAL_BLOCK, Feature.HEADERS_FOOTERS,
                    Feature.FOOTNOTES)
            .support(Feature.TRACKED_CHANGES, Support.PRESERVED_OPAQUE)
            .support(Feature.COMMENTS, Support.PRESERVED_OPAQUE)
            .support(Feature.CHARTS, Support.PRESERVED_OPAQUE)
            .support(Feature.COMPLEX_SCRIPT, Support.FULL)
            .notes("The canonical storage format. Editing a stored DOCX does NOT round-trip "
                    + "through this adapter: OoxmlPackage edits the real package in place and "
                    + "leaves every untouched part byte-identical. Conversion through the pivot "
                    + "model is only for moving between formats, where a new document is expected.")
            .build();

    private final BinaryStore binaries;

    /**
     * An adapter with no blob store: text, tables and fields only.
     *
     * <p>A document containing an image is refused rather than written without
     * it, on the same principle as everywhere else here — a missing 도장 in an
     * export is worse than a failed export.
     */
    public DocxAdapter() {
        this(null);
    }

    public DocxAdapter(BinaryStore binaries) {
        this.binaries = binaries;
    }

    @Override
    public FormatCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean looksLikeThisFormat(byte[] content, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!HwpxAdapter.isZip(content)) {
            return false;
        }
        if (name.endsWith(".docx")) {
            return true;
        }
        // No extension to go on: confirm by looking for the document part.
        try {
            return OoxmlPackage.read(content).hasPart(OoxmlPackage.DOCUMENT_PART);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public InternalDoc read(byte[] content) {
        OoxmlPackage document = OoxmlPackage.read(content);
        Map<String, String> fields = ContentControls.readValues(document.documentPart());

        // The body is parsed as well as preserved. Preserving alone would make
        // docx → docx lossless and docx → HWPX useless: the Korean client would
        // open an HWPX holding the field values and none of the prose.
        List<InternalDoc.Block> blocks =
                new ArrayList<InternalDoc.Block>(new DocxReader(document, binaries).readBlocks());
        // The WHOLE package is carried as an opaque block, not just
        // word/document.xml, so a docx → HWPX → docx round trip cannot lose
        // anything this model does not represent. The document part alone would
        // come back without its styles, numbering, theme or relationships —
        // which is to say as a different document that happened to have the
        // same words in it.
        blocks.add(new InternalDoc.OpaqueBlock(FORMAT_ID, "docx package", content));
        return new InternalDoc(blocks, fields, FORMAT_ID, null);
    }

    /**
     * Writes a DOCX, by replay where possible and by generation otherwise.
     *
     * <p>The two paths are not equivalent and the order matters. A document that
     * came from DOCX carries its original body as an opaque block: replaying it
     * returns the <em>original</em> package content, so a
     * {@code docx → HWPX → docx} round trip gives back the formatting, the
     * styles and the parts this model has no opinion about — not an
     * approximation of them.
     *
     * <p>Generation is for everything else: a document that arrived as HWPX or
     * mdv, where the pivot model is the only source there is. It produces the
     * documented subset and nothing more, which is what the fidelity matrix
     * promises for those pairs.
     */
    @Override
    public byte[] write(InternalDoc document) {
        for (InternalDoc.Block block : document.blocks()) {
            if (block instanceof InternalDoc.OpaqueBlock) {
                InternalDoc.OpaqueBlock opaque = (InternalDoc.OpaqueBlock) block;
                if (FORMAT_ID.equals(opaque.sourceFormat()) && opaque.originalBytes().length > 0) {
                    // Round trip back to where it came from: replay the original
                    // body rather than regenerating an approximation of it.
                    return opaque.originalBytes();
                }
            }
        }
        return new DocxWriter(binaries).write(document);
    }
}
