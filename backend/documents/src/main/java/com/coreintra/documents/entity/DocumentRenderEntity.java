package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * An archived render, with everything that determined how it came out (brief 6.4).
 *
 * <p>The lookup key is (document, version, format, {@link #configFingerprint}), and the
 * fingerprint covers the renderer version, the template version, the resolved font set,
 * the substitutions and the locale - everything that could make the same document
 * produce different bytes. A hit here is returned in preference to re-rendering, which
 * is both the cache and the reproducibility contract: if a re-render ever fails to
 * match, the archived row is authoritative and these columns explain what changed.
 */
@Entity
@Table(name = "document_render")
public class DocumentRenderEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private RenderFormat format;

    @Column(name = "config_fingerprint", nullable = false, length = 64)
    private String configFingerprint;

    @Column(name = "output_blob_sha256", nullable = false, length = 64)
    private String outputBlobSha256;

    /** Pinned, never 'latest': 'LibreOffice 24.2.7.2'. */
    @Column(name = "renderer_version", nullable = false, length = 200)
    private String rendererVersion;

    @Column(name = "template_id", length = 36)
    private String templateId;

    @Column(name = "template_version_no")
    private Integer templateVersionNo;

    @Column(name = "locale", length = 16)
    private String locale;

    /** family to content hash, one per line. "The same fonts" is checkable, not assumed. */
    @Column(name = "font_set", nullable = false)
    private String fontSet = "";

    /** Every substitution the resolver made, in words. Empty on a clean render. */
    @Column(name = "substitutions", nullable = false)
    private String substitutions = "";

    /** Format-specific extras: mdv's spec version, theme and buildTime go here. */
    @Column(name = "extra", nullable = false)
    private String extra = "";

    @Column(name = "document_sha256", length = 64)
    private String documentSha256;

    @Column(name = "output_sha256", nullable = false, length = 64)
    private String outputSha256;

    @Column(name = "rendered_at", nullable = false)
    private OffsetDateTime renderedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DocumentRenderEntity() {
    }

    public DocumentRenderEntity(String id, String companyId, String documentId, int versionNo,
            RenderFormat format, String configFingerprint, String outputBlobSha256,
            String rendererVersion, String outputSha256, OffsetDateTime renderedAt) {
        this.id = id;
        this.companyId = companyId;
        this.documentId = documentId;
        this.versionNo = Integer.valueOf(versionNo);
        this.format = format;
        this.configFingerprint = configFingerprint;
        this.outputBlobSha256 = outputBlobSha256;
        this.rendererVersion = rendererVersion;
        this.outputSha256 = outputSha256;
        this.renderedAt = renderedAt;
        this.createdAt = OffsetDateTime.now();
    }

    /** The record of what the render was made from. Set once, at archive time. */
    public void describeInputs(String templateId, Integer templateVersionNo, String locale,
            String fontSet, String substitutions, String extra, String documentSha256) {
        this.templateId = templateId;
        this.templateVersionNo = templateVersionNo;
        this.locale = locale;
        this.fontSet = fontSet == null ? "" : fontSet;
        this.substitutions = substitutions == null ? "" : substitutions;
        this.extra = extra == null ? "" : extra;
        this.documentSha256 = documentSha256;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public RenderFormat format() {
        return format;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    public String outputBlobSha256() {
        return outputBlobSha256;
    }

    public String rendererVersion() {
        return rendererVersion;
    }

    public String templateId() {
        return templateId;
    }

    public Integer templateVersionNo() {
        return templateVersionNo;
    }

    public String locale() {
        return locale;
    }

    public String fontSet() {
        return fontSet;
    }

    public String substitutions() {
        return substitutions;
    }

    public String extra() {
        return extra;
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

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /** True when a substitution was made: this render is not what the template asked for. */
    public boolean hadSubstitutions() {
        return substitutions != null && !substitutions.isEmpty();
    }
}
