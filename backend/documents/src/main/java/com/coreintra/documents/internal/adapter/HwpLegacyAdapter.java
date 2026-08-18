package com.coreintra.documents.internal.adapter;

import com.coreintra.documents.internal.DocumentAdapter;
import com.coreintra.documents.internal.FormatCapabilities;
import com.coreintra.documents.internal.FormatCapabilities.Feature;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.ooxml.OoxmlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Legacy HWP 5.0 (binary) — <b>read-only, deliberately</b>.
 *
 * <p>Korean clients will send .hwp files and they must be importable. Writing
 * them is a different question and the answer here is no:
 *
 * <ul>
 *   <li>The binary format is undocumented in the way HWPX is documented, so a
 *       writer is reverse-engineered and its output is plausible rather than
 *       correct.</li>
 *   <li>A file that 한글 opens with a repair prompt is worse than no file: the
 *       recipient concludes the sender's system is broken, and they are not
 *       entirely wrong.</li>
 *   <li>HWPX exists, is a KS standard, and 한글 reads it.</li>
 * </ul>
 *
 * <p>So {@link #write} refuses with a message that names the alternative rather
 * than producing something approximate that carries a 결재 signature.
 *
 * <p>Backed by hwplib for reading (class major 51, so Java 8 loads it), with
 * LibreOffice's HWP import filter as a fallback <em>reader</em> — it cannot
 * write HWP either.
 */
public class HwpLegacyAdapter implements DocumentAdapter {

    public static final String FORMAT_ID = "hwp";

    private static final FormatCapabilities CAPABILITIES = FormatCapabilities
            .builder(FORMAT_ID, "HWP 5.0 (legacy binary)")
            .mediaType("application/x-hwp")
            .readOnly()
            .full(Feature.HEADINGS, Feature.RUN_EMPHASIS, Feature.LISTS, Feature.TABLES)
            .degraded(Feature.IMAGES, Feature.MERGED_CELLS, Feature.PAGE_BREAKS)
            .dropped(Feature.CONTENT_CONTROLS, Feature.APPROVAL_BLOCK, Feature.HEADERS_FOOTERS,
                    Feature.FOOTNOTES, Feature.TRACKED_CHANGES, Feature.COMMENTS, Feature.CHARTS,
                    Feature.COMPLEX_SCRIPT)
            .notes("Import only. Writing legacy .hwp would mean emitting a reverse-engineered "
                    + "binary; a file that 한글 opens with a repair prompt is worse than no file. "
                    + "Save as HWPX instead — it is the KS X 6101 standard and 한글 reads it. "
                    + "The original uploaded bytes are always retained and downloadable, so "
                    + "nothing is lost by importing.")
            .build();

    @Override
    public FormatCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean looksLikeThisFormat(byte[] content, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        // D0 CF 11 E0 — the OLE2 compound-file header. Shared with .doc, so the
        // extension disambiguates.
        boolean ole2 = content != null && content.length > 8
                && (content[0] & 0xFF) == 0xD0 && (content[1] & 0xFF) == 0xCF
                && (content[2] & 0xFF) == 0x11 && (content[3] & 0xFF) == 0xE0;
        return ole2 && name.endsWith(".hwp");
    }

    @Override
    public InternalDoc read(byte[] content) {
        if (!looksLikeThisFormat(content, "x.hwp")) {
            throw new OoxmlException(
                    "this file is not a legacy HWP 5.0 document. If it is an HWPX file, upload it "
                            + "with the .hwpx extension so the correct reader is used.");
        }
        List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
        Map<String, String> fields = new LinkedHashMap<String, String>();
        return new InternalDoc(blocks, fields, FORMAT_ID, null);
    }

    @Override
    public byte[] write(InternalDoc document) {
        throw new UnsupportedOperationException(
                "이 시스템은 레거시 .hwp 파일로 저장하지 않습니다. HWPX로 저장해 주십시오. "
                        + "(Legacy .hwp is read-only in this system. Save as HWPX instead — it is "
                        + "the KS X 6101 open standard and 한글 opens it natively. Writing legacy "
                        + "binary HWP would mean emitting a reverse-engineered file that may not "
                        + "open cleanly, which is worse than not offering it.)");
    }
}
