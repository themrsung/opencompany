package com.coreintra.documents.internal;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.internal.FormatCapabilities.Feature;
import com.coreintra.documents.internal.FormatCapabilities.Support;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The fidelity matrix as <em>data</em>, one row per format pair.
 *
 * <h2>Why a pair and not a format</h2>
 *
 * <p>{@link FormatCapabilities} says what one format can carry. That is not the
 * question a user has at export time, which is always about a pair: "I am
 * sending this HWPX to a client as a DOCX — what changes?" The answer depends on
 * both ends, and on neither alone.
 *
 * <h2>Why this is data and not the generated Markdown</h2>
 *
 * <p>§13 requires the relevant row to be shown in the interface when someone
 * exports. A Markdown document cannot be shown as a row: the UI needs the
 * feature, the verdict and the wording, in both languages, as values it can lay
 * out. {@code docs/documents/fidelity.md} is generated from this same object, so
 * the page and the dialog cannot disagree.
 */
public final class FidelityMatrix implements Serializable {

    private static final long serialVersionUID = 1L;

    /** One feature's fate in one direction. */
    public static final class Row implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Feature feature;
        private final Support support;

        Row(Feature feature, Support support) {
            this.feature = feature;
            this.support = support;
        }

        public Feature feature() {
            return feature;
        }

        public Support support() {
            return support;
        }

        /** "tables survive", for the dialog. */
        public String describeEn() {
            return feature.label() + " " + support.description();
        }

        public String describeKo() {
            return feature.labelKo() + " " + support.descriptionKo();
        }
    }

    /** What happens converting one format to another. */
    public static final class Pair implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String fromFormatId;
        private final String fromDisplayName;
        private final String toFormatId;
        private final String toDisplayName;
        private final boolean supported;
        private final String unsupportedReason;
        private final List<Row> rows;
        private final String note;

        Pair(String fromFormatId, String fromDisplayName, String toFormatId,
                String toDisplayName, boolean supported, String unsupportedReason,
                List<Row> rows, String note) {
            this.fromFormatId = fromFormatId;
            this.fromDisplayName = fromDisplayName;
            this.toFormatId = toFormatId;
            this.toDisplayName = toDisplayName;
            this.supported = supported;
            this.unsupportedReason = unsupportedReason;
            this.rows = Immutables.copyOf(rows);
            this.note = note;
        }

        public String fromFormatId() {
            return fromFormatId;
        }

        public String fromDisplayName() {
            return fromDisplayName;
        }

        public String toFormatId() {
            return toFormatId;
        }

        public String toDisplayName() {
            return toDisplayName;
        }

        /** False when the target cannot be written at all — legacy {@code .hwp}. */
        public boolean isSupported() {
            return supported;
        }

        public String unsupportedReason() {
            return unsupportedReason;
        }

        public List<Row> rows() {
            return rows;
        }

        /** A loss belonging to this pair rather than to either format. */
        public String note() {
            return note;
        }

        /** Only the rows a user needs warning about. */
        public List<Row> concerns() {
            List<Row> concerns = new ArrayList<Row>();
            for (Row row : rows) {
                if (row.support().needsWarning()) {
                    concerns.add(row);
                }
            }
            return Immutables.copyOf(concerns);
        }

        public Support support(Feature feature) {
            for (Row row : rows) {
                if (row.feature() == feature) {
                    return row.support();
                }
            }
            return Support.DROPPED;
        }
    }

    private final List<Pair> pairs;

    private FidelityMatrix(List<Pair> pairs) {
        this.pairs = Immutables.copyOf(pairs);
    }

    static FidelityMatrix of(List<DocumentAdapter> adapters) {
        List<Pair> pairs = new ArrayList<Pair>();
        for (DocumentAdapter source : adapters) {
            for (DocumentAdapter target : adapters) {
                pairs.add(pairOf(source.capabilities(), target.capabilities()));
            }
        }
        return new FidelityMatrix(pairs);
    }

    private static Pair pairOf(FormatCapabilities from, FormatCapabilities to) {
        List<Row> rows = new ArrayList<Row>();
        boolean sameFormat = from.formatId().equals(to.formatId());
        for (int index = 0; index < Feature.values().length; index++) {
            Feature feature = Feature.values()[index];
            Support support = sameFormat
                    ? from.support(feature)
                    : worseOf(from.support(feature), to.support(feature));
            rows.add(new Row(feature, support));
        }
        String reason = to.canWrite() ? null
                : to.displayName() + " is read-only in this system: documents can be imported "
                        + "from it and never written back to it.";
        return new Pair(from.formatId(), from.displayName(), to.formatId(), to.displayName(),
                to.canWrite(), reason, rows, from.pairNote(to.formatId()));
    }

    /**
     * The worse of two verdicts.
     *
     * <p>Ranked deliberately: <b>full</b>, then <b>degraded</b>, then
     * <b>preserved-but-opaque</b>, then <b>dropped</b>. Degraded outranks
     * preserved because a user would rather have content they can still edit
     * that looks slightly different than content that is pixel-exact and frozen.
     * Reasonable people could order those two the other way; what matters is
     * that the order is stated rather than emergent.
     */
    private static Support worseOf(Support left, Support right) {
        return rank(left) <= rank(right) ? left : right;
    }

    private static int rank(Support support) {
        if (support == Support.FULL) {
            return 3;
        }
        if (support == Support.DEGRADED) {
            return 2;
        }
        if (support == Support.PRESERVED_OPAQUE) {
            return 1;
        }
        return 0;
    }

    public List<Pair> pairs() {
        return pairs;
    }

    /** @throws IllegalArgumentException if either format is not registered */
    public Pair pair(String fromFormatId, String toFormatId) {
        for (Pair pair : pairs) {
            if (pair.fromFormatId().equals(fromFormatId)
                    && pair.toFormatId().equals(toFormatId)) {
                return pair;
            }
        }
        throw new IllegalArgumentException(
                "no fidelity row for " + fromFormatId + " → " + toFormatId
                        + ". Both formats must be registered adapters.");
    }

    /**
     * The matrix as JSON, for the export dialog.
     *
     * <p>Hand-rolled rather than reached for through a mapper, matching
     * {@code RenderMetadataCodec}: this module has no Jackson dependency and
     * should not gain one to emit fifteen fields.
     */
    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\"pairs\":[");
        for (int index = 0; index < pairs.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            appendPair(out, pairs.get(index));
        }
        out.append("]}");
        return out.toString();
    }

    private static void appendPair(StringBuilder out, Pair pair) {
        out.append("{\"from\":\"").append(escape(pair.fromFormatId()))
           .append("\",\"fromName\":\"").append(escape(pair.fromDisplayName()))
           .append("\",\"to\":\"").append(escape(pair.toFormatId()))
           .append("\",\"toName\":\"").append(escape(pair.toDisplayName()))
           .append("\",\"supported\":").append(pair.isSupported());
        if (pair.unsupportedReason() != null) {
            out.append(",\"unsupportedReason\":\"").append(escape(pair.unsupportedReason()))
               .append('"');
        }
        if (pair.note() != null) {
            out.append(",\"note\":\"").append(escape(pair.note())).append('"');
        }
        out.append(",\"rows\":[");
        for (int index = 0; index < pair.rows().size(); index++) {
            Row row = pair.rows().get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"feature\":\"").append(row.feature().name())
               .append("\",\"labelEn\":\"").append(escape(row.feature().label()))
               .append("\",\"labelKo\":\"").append(escape(row.feature().labelKo()))
               .append("\",\"support\":\"").append(row.support().name())
               .append("\",\"verdictEn\":\"").append(escape(row.support().description()))
               .append("\",\"verdictKo\":\"").append(escape(row.support().descriptionKo()))
               .append("\"}");
        }
        out.append("]}");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                out.append('\\').append(character);
            } else if (character == '\n') {
                out.append("\\n");
            } else if (character < 0x20) {
                out.append(' ');
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }
}
