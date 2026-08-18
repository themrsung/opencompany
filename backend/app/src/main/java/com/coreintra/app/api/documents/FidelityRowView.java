package com.coreintra.app.api.documents;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.internal.FidelityMatrix;
import java.util.ArrayList;
import java.util.List;

/**
 * What one export direction costs, as data the export dialog can lay out.
 *
 * <p>§6.5 requires the relevant row of the fidelity matrix be surfaced in the UI
 * at export time, and §13 tests for it. A Markdown page cannot be shown as a
 * row, so this is the same declarations the page is generated from, in both
 * languages, with the concerns already picked out — a dialog should not have to
 * decide which of eleven features are worth mentioning.
 *
 * <p>{@link #isAvailable()} is false for PDF, DOC and HTML. Nothing in this
 * system reads those back, so there is no pair to describe, and saying "no
 * losses" would be a lie of omission. The reason is returned instead.
 */
public class FidelityRowView {

    /** One feature's fate, in both languages, because Korean is the default locale. */
    public static class Feature {
        private final String feature;
        private final String support;
        private final String describeKo;
        private final String describeEn;

        Feature(FidelityMatrix.Row row) {
            this.feature = row.feature().name();
            this.support = row.support().name();
            this.describeKo = row.describeKo();
            this.describeEn = row.describeEn();
        }

        public String getFeature() {
            return feature;
        }

        /** FULL, DEGRADED, PRESERVED_OPAQUE or DROPPED. */
        public String getSupport() {
            return support;
        }

        public String getDescribeKo() {
            return describeKo;
        }

        public String getDescribeEn() {
            return describeEn;
        }
    }

    private final boolean available;
    private final String unavailableReason;
    private final String fromFormat;
    private final String fromDisplayName;
    private final String toFormat;
    private final String toDisplayName;
    private final boolean supported;
    private final String unsupportedReason;
    private final String note;
    private final List<Feature> concerns;
    private final List<Feature> features;

    private FidelityRowView(FidelityMatrix.Pair pair) {
        this.available = true;
        this.unavailableReason = null;
        this.fromFormat = pair.fromFormatId();
        this.fromDisplayName = pair.fromDisplayName();
        this.toFormat = pair.toFormatId();
        this.toDisplayName = pair.toDisplayName();
        this.supported = pair.isSupported();
        this.unsupportedReason = pair.unsupportedReason();
        this.note = pair.note();
        this.concerns = wrap(pair.concerns());
        this.features = wrap(pair.rows());
    }

    private FidelityRowView(String fromFormat, String toFormat, String reason) {
        this.available = false;
        this.unavailableReason = reason;
        this.fromFormat = fromFormat;
        this.fromDisplayName = null;
        this.toFormat = toFormat;
        this.toDisplayName = null;
        this.supported = true;
        this.unsupportedReason = null;
        this.note = null;
        this.concerns = Immutables.listOf();
        this.features = Immutables.listOf();
    }

    static FidelityRowView of(FidelityMatrix.Pair pair) {
        return new FidelityRowView(pair);
    }

    static FidelityRowView unavailable(String fromFormat, String toFormat, String reason) {
        return new FidelityRowView(fromFormat, toFormat, reason);
    }

    /** False when one end of the pair has no adapter, so no honest row exists. */
    public boolean isAvailable() {
        return available;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public String getFromFormat() {
        return fromFormat;
    }

    public String getFromDisplayName() {
        return fromDisplayName;
    }

    public String getToFormat() {
        return toFormat;
    }

    public String getToDisplayName() {
        return toDisplayName;
    }

    /** False when the target is read-only in this system and cannot be written at all. */
    public boolean isSupported() {
        return supported;
    }

    public String getUnsupportedReason() {
        return unsupportedReason;
    }

    /** The pair's own caveat, where one format has something specific to say about the other. */
    public String getNote() {
        return note;
    }

    /** Only what degrades, is preserved opaquely, or is dropped. What a dialog shows. */
    public List<Feature> getConcerns() {
        return concerns;
    }

    /** Every feature, including the ones that survive. What a matrix page shows. */
    public List<Feature> getFeatures() {
        return features;
    }

    private static List<Feature> wrap(List<FidelityMatrix.Row> rows) {
        List<Feature> wrapped = new ArrayList<Feature>(rows.size());
        for (FidelityMatrix.Row row : rows) {
            wrapped.add(new Feature(row));
        }
        return Immutables.copyOf(wrapped);
    }
}
