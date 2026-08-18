package com.coreintra.documents.font;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * One installed font, and who takes responsibility for it.
 *
 * <h2>Licensing is the client's, and the UI says so in words</h2>
 *
 * <p>Clients install their own fonts — any language, any script, any format. We
 * neither verify nor indemnify. Uploading therefore requires an explicit
 * acknowledgement recorded on this row: who accepted it, when, and what they
 * accepted. The font's own embedding permission ({@code fsType}) is read out of
 * its metadata and shown alongside, read-only, so the person ticking the box can
 * see what they are agreeing about rather than being asked to trust us.
 *
 * <p>함초롬바탕 and 함초롬돋움 are Hancom-licensed and are never bundled. A
 * client who owns 한글 installs them here under exactly this section, and that
 * is the supported answer rather than a limitation.
 */
public final class FontRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Where a font came from. Affects nothing but honesty in the manager UI. */
    public enum Source {
        /** Shipped with the product. OFL or similar; we hold the rights to redistribute. */
        BUNDLED,
        /** Uploaded by the client, under the client's own licence. */
        CLIENT_UPLOADED,
        /** Present on the host, e.g. mounted for a bulk on-prem install. */
        HOST_PROVIDED
    }

    /**
     * The font's own declared embedding permission, read from its OS/2 table.
     *
     * <p>Reported, never enforced by us — honouring it is the client's
     * obligation, and pretending otherwise would imply a check we do not do.
     */
    public enum EmbeddingPermission {
        INSTALLABLE,
        RESTRICTED,
        PRINT_AND_PREVIEW,
        EDITABLE,
        /** The font declares nothing, or the table could not be read. */
        UNKNOWN
    }

    private final String id;
    private final String family;
    private final String style;
    private final String fileFormat;
    private final String blobSha256;
    private final Set<String> scriptCoverage;
    private final Source source;
    private final EmbeddingPermission embeddingPermission;
    private final String uploadedByAccountId;
    private final OffsetDateTime uploadedAt;
    private final String licenceAcknowledgementText;
    private final boolean enabled;

    private FontRecord(Builder builder) {
        this.id = builder.id;
        this.family = builder.family;
        this.style = builder.style;
        this.fileFormat = builder.fileFormat;
        this.blobSha256 = builder.blobSha256;
        this.scriptCoverage = Immutables.setCopyOf(builder.scriptCoverage);
        this.source = builder.source;
        this.embeddingPermission = builder.embeddingPermission;
        this.uploadedByAccountId = builder.uploadedByAccountId;
        this.uploadedAt = builder.uploadedAt;
        this.licenceAcknowledgementText = builder.licenceAcknowledgementText;
        this.enabled = builder.enabled;
    }

    public static Builder builder(String id, String family) {
        return new Builder(id, family);
    }

    public String id() {
        return id;
    }

    /** The family name as documents reference it. The lookup key. */
    public String family() {
        return family;
    }

    public String style() {
        return style;
    }

    /** TTF, OTF, TTC, OTC, WOFF2 — no allowlist, whatever fontconfig will take. */
    public String fileFormat() {
        return fileFormat;
    }

    /** Content hash in the blob store. Shared by all three consumers. */
    public String blobSha256() {
        return blobSha256;
    }

    /** ISO 15924 script codes this font covers: Hang, Latn, Hani, Arab, Deva. */
    public Set<String> scriptCoverage() {
        return scriptCoverage;
    }

    public boolean covers(String scriptCode) {
        return scriptCoverage.contains(scriptCode);
    }

    public Source source() {
        return source;
    }

    public EmbeddingPermission embeddingPermission() {
        return embeddingPermission;
    }

    public String uploadedByAccountId() {
        return uploadedByAccountId;
    }

    public OffsetDateTime uploadedAt() {
        return uploadedAt;
    }

    /** The exact words the uploader accepted, stored verbatim for the audit trail. */
    public String licenceAcknowledgementText() {
        return licenceAcknowledgementText;
    }

    /** Disabled fonts stay installed and stop being offered. Removal is separate. */
    public boolean isEnabled() {
        return enabled;
    }

    public static final class Builder {
        private final String id;
        private final String family;
        private String style = "Regular";
        private String fileFormat;
        private String blobSha256;
        private Set<String> scriptCoverage = Immutables.setOf();
        private Source source = Source.CLIENT_UPLOADED;
        private EmbeddingPermission embeddingPermission = EmbeddingPermission.UNKNOWN;
        private String uploadedByAccountId;
        private OffsetDateTime uploadedAt;
        private String licenceAcknowledgementText;
        private boolean enabled = true;

        Builder(String id, String family) {
            if (Texts.isBlank(family)) {
                throw new IllegalArgumentException("a font needs a family name; it is the key "
                        + "documents reference it by");
            }
            this.id = id;
            this.family = Texts.strip(family);
        }

        public Builder style(String value) {
            this.style = value;
            return this;
        }

        public Builder fileFormat(String value) {
            this.fileFormat = value;
            return this;
        }

        public Builder blobSha256(String value) {
            this.blobSha256 = value;
            return this;
        }

        public Builder scriptCoverage(Set<String> value) {
            this.scriptCoverage = value;
            return this;
        }

        public Builder source(Source value) {
            this.source = value;
            return this;
        }

        public Builder embeddingPermission(EmbeddingPermission value) {
            this.embeddingPermission = value;
            return this;
        }

        public Builder acknowledgedBy(String accountId, OffsetDateTime at, String text) {
            this.uploadedByAccountId = accountId;
            this.uploadedAt = at;
            this.licenceAcknowledgementText = text;
            return this;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        /**
         * @throws IllegalStateException if a client upload has no recorded
         *         acknowledgement — the record is the only evidence that anyone
         *         accepted responsibility, so it cannot be optional
         */
        public FontRecord build() {
            if (source == Source.CLIENT_UPLOADED
                    && (Texts.isBlank(licenceAcknowledgementText) || uploadedByAccountId == null)) {
                throw new IllegalStateException(
                        "a client-uploaded font must record who accepted the licence terms and "
                                + "what they accepted. Without it there is no evidence anyone took "
                                + "responsibility, which is the entire point of the acknowledgement.");
            }
            return new FontRecord(this);
        }
    }
}
