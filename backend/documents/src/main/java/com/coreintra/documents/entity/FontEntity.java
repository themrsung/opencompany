package com.coreintra.documents.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Table;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.documents.font.FontRecord;

/**
 * An installed font, and who took responsibility for it.
 *
 * <p>One font store, three consumers (brief 6.9): the conversion worker's fontconfig,
 * the browser editor's webfont, and mdv's {@code pdf.fonts}. All three read from this
 * row, so they cannot disagree about what is installed.
 *
 * <p>{@code licenceAcknowledgementText} is the exact wording the uploader ticked. It is
 * the only evidence that anyone took responsibility for the licence, which is the entire
 * point of asking, so a client upload without it is refused at both this layer and the
 * database's.
 */
@Entity
@Table(name = "font")
public class FontEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** NULL means shipped with the product and available to every company. */
    @Column(name = "company_id", length = 36)
    private String companyId;

    @Column(name = "family", nullable = false, length = 200)
    private String family;

    @Column(name = "style", nullable = false, length = 100)
    private String style = "Regular";

    @Column(name = "file_format", nullable = false, length = 16)
    private String fileFormat;

    @Column(name = "blob_sha256", nullable = false, length = 64)
    private String blobSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private FontRecord.Source source;

    /** Read out of the font's OS/2 table. Reported, never enforced by us. */
    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_permission", nullable = false, length = 24)
    private FontRecord.EmbeddingPermission embeddingPermission = FontRecord.EmbeddingPermission.UNKNOWN;

    /**
     * The raw OS/2 fsType bits, kept beside our reading of them.
     *
     * <p>The manager UI shows this verbatim. If our parsing is coarser than the font's
     * actual declaration, the client can still see what they are agreeing about - which is
     * the only reason the value is surfaced at all.
     */
    @Column(name = "fs_type_raw")
    private Integer fsTypeRaw;

    @Column(name = "uploaded_by_account_id", length = 36)
    private String uploadedByAccountId;

    @Column(name = "uploaded_at")
    private OffsetDateTime uploadedAt;

    @Column(name = "licence_acknowledgement_text")
    private String licenceAcknowledgementText;

    /**
     * When the font stopped being offered, or NULL while it is.
     *
     * <p>A timestamp rather than a flag because "when did this stop being used?" is the
     * first question asked when a PDF from last month looks wrong.
     */
    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    /** ISO 15924 script codes this font covers: Hang, Latn, Hani, Arab, Deva. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "font_script_coverage", joinColumns = @JoinColumn(name = "font_id"))
    @Column(name = "script_code", length = 8)
    private Set<String> scriptCoverage = new LinkedHashSet<String>();

    protected FontEntity() {
    }

    public FontEntity(String id, String companyId, String family, String style, String fileFormat,
            String blobSha256, FontRecord.Source source, Set<String> scriptCoverage) {
        this.id = id;
        this.companyId = companyId;
        this.family = family;
        this.style = style;
        this.fileFormat = fileFormat;
        this.blobSha256 = blobSha256;
        this.source = source;
        this.scriptCoverage = new LinkedHashSet<String>(scriptCoverage);
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Records who accepted the licence, when, and in what words.
     *
     * @throws IllegalArgumentException if the acknowledgement is blank - an unrecorded
     *         acceptance is not an acceptance, and the row would be unenforceable evidence
     */
    public void acknowledgedBy(String accountId, OffsetDateTime at, String text) {
        if (Texts.isBlank(accountId)) {
            throw new IllegalArgumentException("an acknowledgement needs the account that made it");
        }
        if (Texts.isBlank(text)) {
            throw new IllegalArgumentException(
                    "an acknowledgement needs the words that were accepted; "
                    + "without them there is no evidence anyone took responsibility");
        }
        this.uploadedByAccountId = accountId;
        this.uploadedAt = at;
        this.licenceAcknowledgementText = text;
    }

    /** What the font declares about embedding: our reading of it, and the raw bits. */
    public void declaresEmbedding(FontRecord.EmbeddingPermission permission, Integer fsTypeRaw) {
        this.embeddingPermission =
                permission == null ? FontRecord.EmbeddingPermission.UNKNOWN : permission;
        this.fsTypeRaw = fsTypeRaw;
    }

    public void disable(OffsetDateTime at) {
        this.disabledAt = at;
    }

    public void enable() {
        this.disabledAt = null;
    }

    public void retire(OffsetDateTime at) {
        this.retiredAt = at;
        if (this.disabledAt == null) {
            this.disabledAt = at;
        }
    }

    /** The pure-logic view. What the resolver and the fidelity story actually consume. */
    public FontRecord toRecord() {
        FontRecord.Builder builder = FontRecord.builder(id, family)
                .style(style)
                .fileFormat(fileFormat)
                .blobSha256(blobSha256)
                .scriptCoverage(scriptCoverage)
                .source(source)
                .embeddingPermission(embeddingPermission)
                .enabled(isEnabled());
        if (licenceAcknowledgementText != null) {
            builder.acknowledgedBy(uploadedByAccountId, uploadedAt, licenceAcknowledgementText);
        }
        return builder.build();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String family() {
        return family;
    }

    public String style() {
        return style;
    }

    public String fileFormat() {
        return fileFormat;
    }

    public String blobSha256() {
        return blobSha256;
    }

    public FontRecord.Source source() {
        return source;
    }

    public FontRecord.EmbeddingPermission embeddingPermission() {
        return embeddingPermission;
    }

    /** The font's own fsType bits, unread. NULL when the table could not be read. */
    public Integer fsTypeRaw() {
        return fsTypeRaw;
    }

    public String uploadedByAccountId() {
        return uploadedByAccountId;
    }

    public OffsetDateTime uploadedAt() {
        return uploadedAt;
    }

    public String licenceAcknowledgementText() {
        return licenceAcknowledgementText;
    }

    public boolean isEnabled() {
        return disabledAt == null;
    }

    public OffsetDateTime disabledAt() {
        return disabledAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public Set<String> scriptCoverage() {
        return Immutables.setCopyOf(scriptCoverage);
    }
}
