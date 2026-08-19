package com.coreintra.documents.internal;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one format can carry, declared by its adapter.
 *
 * <h2>Why the fidelity matrix is generated from this</h2>
 *
 * <p>A hand-written fidelity table is out of date the first time an adapter
 * changes, and nobody notices — least of all the user being shown it at export
 * time, who is the one person the table exists for. So each adapter declares
 * what it supports, and {@code docs/documents/fidelity.md} plus the export-time
 * warning are both derived from these declarations.
 *
 * <p>The matrix cannot then claim a fidelity the code does not have.
 */
public final class FormatCapabilities implements Serializable {

    private static final long serialVersionUID = 1L;

    /** How well one feature survives. */
    public enum Support {
        /** Round-trips without loss. */
        FULL("survives", "유지됩니다"),
        /** Survives with visible differences — spacing, exact styling. */
        DEGRADED("degrades", "일부 달라집니다"),
        /** Cannot be represented and is dropped. Must be warned about, loudly. */
        DROPPED("is dropped", "사라집니다"),
        /** Preserved as an opaque block: not editable, but not lost either. */
        PRESERVED_OPAQUE("is preserved but not editable", "보존되지만 편집할 수 없습니다");

        private final String description;
        private final String descriptionKo;

        Support(String description, String descriptionKo) {
            this.description = description;
            this.descriptionKo = descriptionKo;
        }

        public String description() {
            return description;
        }

        /**
         * The same thing in Korean.
         *
         * <p>This text is shown to a user at export time, and Korean is the
         * default locale (§12). An export warning only in English is a warning
         * most of these users will not read.
         */
        public String descriptionKo() {
            return descriptionKo;
        }

        /** True when a user must be told before they save. */
        public boolean needsWarning() {
            return this == DEGRADED || this == DROPPED;
        }
    }

    /** The features the matrix reports on. */
    public enum Feature {
        HEADINGS("headings", "제목 스타일"),
        RUN_EMPHASIS("bold / italic / underline / strikethrough", "굵게 / 기울임 / 밑줄 / 취소선"),
        LISTS("bulleted and numbered lists", "글머리 기호 및 번호 목록"),
        TABLES("tables", "표"),
        MERGED_CELLS("merged table cells", "병합된 셀"),
        IMAGES("images", "그림"),
        PAGE_BREAKS("page breaks", "쪽 나눔"),
        CONTENT_CONTROLS("typed fields (content controls)", "입력 항목 (필드)"),
        APPROVAL_BLOCK("결재란", "결재란"),
        HEADERS_FOOTERS("headers and footers", "머리말 및 꼬리말"),
        FOOTNOTES("footnotes", "각주"),
        TRACKED_CHANGES("tracked changes", "변경 내용 추적"),
        COMMENTS("comments", "메모"),
        CHARTS("charts", "차트"),
        COMPLEX_SCRIPT("complex-script shaping (Arabic, Indic)", "복합 문자 처리 (아랍 문자, 인도계 문자)");

        private final String label;
        private final String labelKo;

        Feature(String label, String labelKo) {
            this.label = label;
            this.labelKo = labelKo;
        }

        public String label() {
            return label;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    private final String formatId;
    private final String displayName;
    private final String mediaType;
    private final boolean canRead;
    private final boolean canWrite;
    private final Map<Feature, Support> features;
    private final String notes;
    private final Map<String, String> pairNotes;

    private FormatCapabilities(Builder builder) {
        this.formatId = builder.formatId;
        this.displayName = builder.displayName;
        this.mediaType = builder.mediaType;
        this.canRead = builder.canRead;
        this.canWrite = builder.canWrite;
        this.features = Immutables.mapCopyOf(builder.features);
        this.notes = builder.notes;
        this.pairNotes = Immutables.mapCopyOf(builder.pairNotes);
    }

    public static Builder builder(String formatId, String displayName) {
        return new Builder(formatId, displayName);
    }

    public String formatId() {
        return formatId;
    }

    public String displayName() {
        return displayName;
    }

    public String mediaType() {
        return mediaType;
    }

    public boolean canRead() {
        return canRead;
    }

    /** False for a read-only format. Legacy {@code .hwp} is the case in point. */
    public boolean canWrite() {
        return canWrite;
    }

    public Support support(Feature feature) {
        Support declared = features.get(feature);
        // Undeclared means unsupported. Defaulting to FULL would let an adapter
        // claim fidelity by omission, which is the failure this class prevents.
        return declared == null ? Support.DROPPED : declared;
    }

    public Map<Feature, Support> features() {
        return features;
    }

    public String notes() {
        return notes;
    }

    /**
     * Notes that only apply when writing <em>to</em> a particular other format.
     *
     * <p>Some losses are a property of the pair rather than of either format on
     * its own. An mdv chart is a live chart in mdv and a picture in DOCX: DOCX
     * has not lost anything it claims to support, and mdv has not failed to
     * export — but the user has stopped being able to edit the chart, and only
     * the pair can say so.
     *
     * @return target format id to note
     */
    public Map<String, String> pairNotes() {
        return pairNotes;
    }

    public String pairNote(String targetFormatId) {
        return pairNotes.get(targetFormatId);
    }

    /**
     * What would be lost writing a document to this format, in plain language.
     *
     * <p>This is the text shown at export time — the "concrete warning drawn
     * from the fidelity matrix" rather than a generic disclaimer. It reports
     * only features the document actually uses.
     */
    public List<String> warningsFor(InternalDoc document) {
        List<String> warnings = new ArrayList<String>();
        if (!canWrite) {
            warnings.add(displayName + " is read-only in this system. Documents can be imported "
                    + "from it, but not written back to it.");
            return Immutables.copyOf(warnings);
        }
        for (Feature feature : featuresUsedBy(document)) {
            Support support = support(feature);
            if (support.needsWarning()) {
                warnings.add(feature.label() + " " + support.description()
                        + " when saving as " + displayName + ".");
            }
        }
        if (document.hasOpaqueContent()) {
            List<String> descriptions = new ArrayList<String>();
            for (InternalDoc.OpaqueBlock block : document.opaqueBlocks()) {
                if (!descriptions.contains(block.description())) {
                    descriptions.add(block.description());
                }
            }
            warnings.add("This document contains content this editor does not understand ("
                    + descriptions + "). It is preserved as-is when saving back to "
                    + "its original format, but cannot be carried into " + displayName + ".");
        }
        return Immutables.copyOf(warnings);
    }

    /** Only the features a document actually uses, so warnings stay relevant. */
    private List<Feature> featuresUsedBy(InternalDoc document) {
        List<Feature> used = new ArrayList<Feature>();
        for (InternalDoc.Block block : document.blocks()) {
            if (block instanceof InternalDoc.Paragraph) {
                InternalDoc.Paragraph paragraph = (InternalDoc.Paragraph) block;
                if (paragraph.headingLevel() > 0) {
                    addOnce(used, Feature.HEADINGS);
                }
                if (paragraph.listStyle() != null) {
                    addOnce(used, Feature.LISTS);
                }
                for (InternalDoc.Run run : paragraph.runs()) {
                    if (run.bold() || run.italic() || run.underline() || run.strike()) {
                        addOnce(used, Feature.RUN_EMPHASIS);
                    }
                }
            } else if (block instanceof InternalDoc.Table) {
                addOnce(used, Feature.TABLES);
            } else if (block instanceof InternalDoc.Image) {
                addOnce(used, Feature.IMAGES);
            } else if (block instanceof InternalDoc.PageBreak) {
                addOnce(used, Feature.PAGE_BREAKS);
            } else if (block instanceof InternalDoc.FieldBlock) {
                addOnce(used, Feature.CONTENT_CONTROLS);
            }
        }
        return used;
    }

    private static void addOnce(List<Feature> list, Feature feature) {
        if (!list.contains(feature)) {
            list.add(feature);
        }
    }

    public static final class Builder {
        private final String formatId;
        private final String displayName;
        private String mediaType;
        private boolean canRead = true;
        private boolean canWrite = true;
        private final Map<Feature, Support> features = new LinkedHashMap<Feature, Support>();
        private final Map<String, String> pairNotes = new LinkedHashMap<String, String>();
        private String notes;

        Builder(String formatId, String displayName) {
            this.formatId = formatId;
            this.displayName = displayName;
        }

        public Builder mediaType(String value) {
            this.mediaType = value;
            return this;
        }

        public Builder readOnly() {
            this.canWrite = false;
            return this;
        }

        public Builder support(Feature feature, Support support) {
            features.put(feature, support);
            return this;
        }

        public Builder full(Feature... supported) {
            for (Feature feature : supported) {
                features.put(feature, Support.FULL);
            }
            return this;
        }

        public Builder degraded(Feature... degraded) {
            for (Feature feature : degraded) {
                features.put(feature, Support.DEGRADED);
            }
            return this;
        }

        public Builder dropped(Feature... dropped) {
            for (Feature feature : dropped) {
                features.put(feature, Support.DROPPED);
            }
            return this;
        }

        public Builder notes(String value) {
            this.notes = value;
            return this;
        }

        /** A loss that only happens writing to {@code targetFormatId}. */
        public Builder pairNote(String targetFormatId, String note) {
            pairNotes.put(targetFormatId, note);
            return this;
        }

        public FormatCapabilities build() {
            return new FormatCapabilities(this);
        }
    }
}
