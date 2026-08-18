package com.coreintra.documents.internal.adapter;

import com.coreintra.documents.internal.DocumentAdapter;
import com.coreintra.documents.internal.FormatCapabilities;
import com.coreintra.documents.internal.FormatCapabilities.Feature;
import com.coreintra.documents.internal.FormatCapabilities.Support;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.ooxml.ContentControls;
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

        List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            blocks.add(new InternalDoc.FieldBlock(field.getKey(), field.getValue()));
        }
        // The body is carried as an opaque block so a docx → HWPX → docx round
        // trip cannot lose anything this model does not represent.
        blocks.add(new InternalDoc.OpaqueBlock(FORMAT_ID, "docx body",
                document.documentPart()));
        return new InternalDoc(blocks, fields, FORMAT_ID, null);
    }

    @Override
    public byte[] write(InternalDoc document) {
        for (InternalDoc.Block block : document.blocks()) {
            if (block instanceof InternalDoc.OpaqueBlock) {
                InternalDoc.OpaqueBlock opaque = (InternalDoc.OpaqueBlock) block;
                if (FORMAT_ID.equals(opaque.sourceFormat())) {
                    // Round trip back to where it came from: replay the original
                    // body rather than regenerating an approximation of it.
                    return opaque.originalBytes();
                }
            }
        }
        throw new UnsupportedOperationException(
                "generating a DOCX from scratch is not implemented in this adapter. Documents are "
                        + "created from a template, whose package is edited in place by "
                        + "OoxmlPackage; this path exists for converting a document that "
                        + "originated elsewhere.");
    }
}
