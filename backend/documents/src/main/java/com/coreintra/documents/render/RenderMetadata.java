package com.coreintra.documents.render;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.font.FontResolver;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that determined how a PDF came out, recorded with the PDF.
 *
 * <h2>Why this is not optional</h2>
 *
 * <p>A rendered 결재 document has to be reproducible years later. The LibreOffice
 * path cannot be made byte-deterministic — the best available is to pin the
 * version and record the inputs — so every render carries the version, the
 * template version, the exact font set, and every substitution that was made.
 *
 * <p>If a regenerated PDF ever fails to match the archived one, this record is
 * what tells you why: a LibreOffice upgrade, a font removed, a template edited.
 * <b>The archived PDF remains authoritative</b> in that situation; this metadata
 * explains the difference rather than excusing it.
 *
 * <p>Immutable. Written once, at render time.
 */
public final class RenderMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String rendererVersion;
    private final String templateId;
    private final int templateVersion;
    private final Map<String, String> fontSet;
    private final List<String> substitutions;
    private final String documentSha256;
    private final String outputSha256;
    private final OffsetDateTime renderedAt;
    private final String locale;
    private final Map<String, String> extra;

    private RenderMetadata(Builder builder) {
        this.rendererVersion = builder.rendererVersion;
        this.templateId = builder.templateId;
        this.templateVersion = builder.templateVersion;
        this.fontSet = Immutables.mapCopyOf(builder.fontSet);
        this.substitutions = Immutables.copyOf(builder.substitutions);
        this.documentSha256 = builder.documentSha256;
        this.outputSha256 = builder.outputSha256;
        this.renderedAt = builder.renderedAt;
        this.locale = builder.locale;
        this.extra = Immutables.mapCopyOf(builder.extra);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** e.g. {@code LibreOffice 24.2.7.2}. Pinned, and recorded on every render. */
    public String rendererVersion() {
        return rendererVersion;
    }

    public String templateId() {
        return templateId;
    }

    /** A submitted document renders identically forever against its own version. */
    public int templateVersion() {
        return templateVersion;
    }

    /** Family to content hash, so "the same fonts" is checkable rather than assumed. */
    public Map<String, String> fontSet() {
        return fontSet;
    }

    /** Every substitution made, in words. Empty on a clean render. */
    public List<String> substitutions() {
        return substitutions;
    }

    public boolean hadSubstitutions() {
        return !substitutions.isEmpty();
    }

    public String documentSha256() {
        return documentSha256;
    }

    public String outputSha256() {
        return outputSha256;
    }

    public OffsetDateTime renderedAt() {
        return renderedAt;
    }

    public String locale() {
        return locale;
    }

    /** Format-specific extras — mdv's spec version, theme and buildTime go here. */
    public Map<String, String> extra() {
        return extra;
    }

    /**
     * Compares against a later render.
     *
     * <p>Returns the reasons they differ, most likely cause first. Empty means
     * the inputs were identical and a byte difference is genuinely unexplained.
     */
    public List<String> explainDifferenceFrom(RenderMetadata other) {
        List<String> differences = new ArrayList<String>();
        if (!equalOrBothNull(rendererVersion, other.rendererVersion)) {
            differences.add("renderer changed: " + rendererVersion + " → " + other.rendererVersion);
        }
        if (templateVersion != other.templateVersion) {
            differences.add("template version changed: " + templateVersion + " → "
                    + other.templateVersion);
        }
        for (Map.Entry<String, String> entry : fontSet.entrySet()) {
            String otherHash = other.fontSet.get(entry.getKey());
            if (otherHash == null) {
                differences.add("font no longer present: " + entry.getKey());
            } else if (!otherHash.equals(entry.getValue())) {
                differences.add("font changed: " + entry.getKey());
            }
        }
        for (String family : other.fontSet.keySet()) {
            if (!fontSet.containsKey(family)) {
                differences.add("font added: " + family);
            }
        }
        if (!substitutions.equals(other.substitutions)) {
            differences.add("font substitutions differ: " + substitutions + " → "
                    + other.substitutions);
        }
        if (!equalOrBothNull(locale, other.locale)) {
            differences.add("locale changed: " + locale + " → " + other.locale);
        }
        return Immutables.copyOf(differences);
    }

    private static boolean equalOrBothNull(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    public static final class Builder {
        private String rendererVersion;
        private String templateId;
        private int templateVersion;
        private final Map<String, String> fontSet = new LinkedHashMap<String, String>();
        private final List<String> substitutions = new ArrayList<String>();
        private String documentSha256;
        private String outputSha256;
        private OffsetDateTime renderedAt;
        private String locale;
        private final Map<String, String> extra = new LinkedHashMap<String, String>();

        public Builder rendererVersion(String value) {
            this.rendererVersion = value;
            return this;
        }

        public Builder template(String id, int version) {
            this.templateId = id;
            this.templateVersion = version;
            return this;
        }

        public Builder font(String family, String contentHash) {
            this.fontSet.put(family, contentHash);
            return this;
        }

        /** Records every substitution the resolver reported. */
        public Builder fontResolutions(List<FontResolver.Resolution> resolutions) {
            for (FontResolver.Resolution resolution : resolutions) {
                if (resolution.isSubstituted() || resolution.isUnresolved()) {
                    substitutions.add(resolution.toString());
                }
            }
            return this;
        }

        public Builder documentSha256(String value) {
            this.documentSha256 = value;
            return this;
        }

        public Builder outputSha256(String value) {
            this.outputSha256 = value;
            return this;
        }

        public Builder renderedAt(OffsetDateTime value) {
            this.renderedAt = value;
            return this;
        }

        public Builder locale(String value) {
            this.locale = value;
            return this;
        }

        public Builder extra(String key, String value) {
            this.extra.put(key, value);
            return this;
        }

        /**
         * @throws IllegalStateException if the renderer version is missing —
         *         without it the record cannot explain a future difference,
         *         which is the only reason it exists
         */
        public RenderMetadata build() {
            if (rendererVersion == null || rendererVersion.trim().isEmpty()) {
                throw new IllegalStateException(
                        "renderMetadata needs the renderer version. Without it, a regenerated PDF "
                                + "that differs from the archived one cannot be explained, and the "
                                + "reproducibility contract is unenforceable.");
            }
            return new RenderMetadata(this);
        }
    }
}
