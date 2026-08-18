package com.coreintra.documents.internal;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pivot format: {@code HWP/HWPX ⇄ InternalDoc ⇄ DOCX/mdv}.
 *
 * <p>One adapter per format, converting to and from this. The alternative —
 * direct converters between every pair — costs N×N implementations and, worse,
 * N×N sets of quirks. Adding HWPX to a system with DOCX, DOC, PDF and mdv would
 * mean seven new converters; here it means one adapter.
 *
 * <h2>What this model deliberately is not</h2>
 *
 * <p>It is not a complete representation of any of these formats, and it does
 * not try to be. It carries the constrained subset the in-app editor supports —
 * headings, paragraphs, runs with basic emphasis, lists, tables, images, page
 * breaks, and content controls — plus {@link OpaqueBlock} for everything else.
 *
 * <p>Anything outside the subset round-trips as an opaque block carrying its
 * original bytes. That is what lets a real-world document survive an edit to
 * one field without losing the chart, the VML shape or the custom XML part that
 * this system has no opinion about. <b>Nothing is ever silently dropped.</b>
 */
public final class InternalDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Anything that can appear in the body. */
    public interface Block extends Serializable {
        /** True when this block survived without being understood. */
        boolean isOpaque();
    }

    /** Character-level emphasis. The documented subset, and nothing more. */
    public static final class Run implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String text;
        private final boolean bold;
        private final boolean italic;
        private final boolean underline;
        private final boolean strike;
        private final String fontFamily;

        public Run(String text, boolean bold, boolean italic, boolean underline, boolean strike,
                String fontFamily) {
            this.text = text == null ? "" : text;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strike = strike;
            this.fontFamily = fontFamily;
        }

        public static Run plain(String text) {
            return new Run(text, false, false, false, false, null);
        }

        public String text() {
            return text;
        }

        public boolean bold() {
            return bold;
        }

        public boolean italic() {
            return italic;
        }

        public boolean underline() {
            return underline;
        }

        public boolean strike() {
            return strike;
        }

        /**
         * The font this run names, or null to inherit.
         *
         * <p>Carried through conversion so the font resolver can warn about a
         * family the target environment does not have — a Korean document
         * naming 함초롬바탕 must produce a substitution warning, not silence.
         */
        public String fontFamily() {
            return fontFamily;
        }
    }

    public static final class Paragraph implements Block {
        private static final long serialVersionUID = 1L;

        private final List<Run> runs;
        private final int headingLevel;
        private final String listStyle;
        private final int listLevel;

        public Paragraph(List<Run> runs, int headingLevel, String listStyle, int listLevel) {
            this.runs = Immutables.copyOf(runs);
            this.headingLevel = headingLevel;
            this.listStyle = listStyle;
            this.listLevel = listLevel;
        }

        public static Paragraph of(String text) {
            List<Run> runs = new ArrayList<Run>();
            runs.add(Run.plain(text));
            return new Paragraph(runs, 0, null, 0);
        }

        public static Paragraph heading(String text, int level) {
            List<Run> runs = new ArrayList<Run>();
            runs.add(Run.plain(text));
            return new Paragraph(runs, level, null, 0);
        }

        public List<Run> runs() {
            return runs;
        }

        /** 1-6 for a heading, 0 for body text. */
        public int headingLevel() {
            return headingLevel;
        }

        /** "bullet", "ordered", or null when not a list item. */
        public String listStyle() {
            return listStyle;
        }

        public int listLevel() {
            return listLevel;
        }

        public String text() {
            StringBuilder text = new StringBuilder();
            for (Run run : runs) {
                text.append(run.text());
            }
            return text.toString();
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    public static final class Table implements Block {
        private static final long serialVersionUID = 1L;

        private final List<List<List<Block>>> rows;
        private final boolean headerRow;

        public Table(List<List<List<Block>>> rows, boolean headerRow) {
            this.rows = Immutables.copyOf(rows);
            this.headerRow = headerRow;
        }

        /** Rows of cells; each cell holds blocks, because cells contain paragraphs. */
        public List<List<List<Block>>> rows() {
            return rows;
        }

        public boolean hasHeaderRow() {
            return headerRow;
        }

        public int rowCount() {
            return rows.size();
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    public static final class Image implements Block {
        private static final long serialVersionUID = 1L;

        private final String blobSha256;
        private final String mediaType;
        private final String altText;
        private final int widthEmu;
        private final int heightEmu;

        public Image(String blobSha256, String mediaType, String altText, int widthEmu,
                int heightEmu) {
            this.blobSha256 = blobSha256;
            this.mediaType = mediaType;
            this.altText = altText;
            this.widthEmu = widthEmu;
            this.heightEmu = heightEmu;
        }

        public String blobSha256() {
            return blobSha256;
        }

        public String mediaType() {
            return mediaType;
        }

        /** Required for the accessibility floor; empty means decorative. */
        public String altText() {
            return altText;
        }

        /** English Metric Units — OOXML's unit, and lossless into HWPX's. */
        public int widthEmu() {
            return widthEmu;
        }

        public int heightEmu() {
            return heightEmu;
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    public static final class PageBreak implements Block {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    /** A typed field. Becomes a {@code w:sdt} in DOCX, a field in HWPX. */
    public static final class FieldBlock implements Block {
        private static final long serialVersionUID = 1L;

        private final String tag;
        private final String value;

        public FieldBlock(String tag, String value) {
            this.tag = tag;
            this.value = value;
        }

        public String tag() {
            return tag;
        }

        public String value() {
            return value;
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    /**
     * Content this model does not understand, carried through verbatim.
     *
     * <p>The single most important block type here. A real client document has
     * charts, embedded objects, tracked changes, VML shapes and vendor
     * extensions. Dropping them on an edit would be silent data loss on
     * somebody's contract, so anything unrecognised becomes one of these and is
     * written back exactly as it arrived.
     */
    public static final class OpaqueBlock implements Block {
        private static final long serialVersionUID = 1L;

        private final String sourceFormat;
        private final String description;
        private final byte[] originalBytes;

        public OpaqueBlock(String sourceFormat, String description, byte[] originalBytes) {
            this.sourceFormat = sourceFormat;
            this.description = description;
            this.originalBytes = originalBytes == null ? new byte[0] : originalBytes.clone();
        }

        /** Which format these bytes are in. An opaque block is only replayable there. */
        public String sourceFormat() {
            return sourceFormat;
        }

        /** Human description for the fidelity warning: "chart", "tracked change". */
        public String description() {
            return description;
        }

        public byte[] originalBytes() {
            return originalBytes.clone();
        }

        @Override
        public boolean isOpaque() {
            return true;
        }
    }

    private final List<Block> blocks;
    private final Map<String, String> fieldValues;
    private final String sourceFormat;
    private final String originalBlobSha256;

    public InternalDoc(List<Block> blocks, Map<String, String> fieldValues, String sourceFormat,
            String originalBlobSha256) {
        this.blocks = Immutables.copyOf(blocks);
        this.fieldValues = Immutables.mapCopyOf(
                fieldValues == null ? new LinkedHashMap<String, String>() : fieldValues);
        this.sourceFormat = sourceFormat;
        this.originalBlobSha256 = originalBlobSha256;
    }

    public List<Block> blocks() {
        return blocks;
    }

    /** Extracted field values, queryable without opening the document. */
    public Map<String, String> fieldValues() {
        return fieldValues;
    }

    public String sourceFormat() {
        return sourceFormat;
    }

    /**
     * The original uploaded bytes.
     *
     * <p>Never overwritten and downloadable forever. An editable conversion is
     * an interpretation; the source is the fact.
     */
    public String originalBlobSha256() {
        return originalBlobSha256;
    }

    /** Blocks carried through without being understood. Drives the save-time warning. */
    public List<OpaqueBlock> opaqueBlocks() {
        List<OpaqueBlock> opaque = new ArrayList<OpaqueBlock>();
        for (Block block : blocks) {
            if (block instanceof OpaqueBlock) {
                opaque.add((OpaqueBlock) block);
            }
        }
        return Immutables.copyOf(opaque);
    }

    public boolean hasOpaqueContent() {
        return !opaqueBlocks().isEmpty();
    }
}
