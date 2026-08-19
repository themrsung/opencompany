package com.coreintra.documents.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * One version, one schema, one body per language.
 *
 * <p>The parallel Korean and English bodies are the same version deliberately: a
 * client who edits the Korean wording must not silently create a version whose
 * English body is a different document. They are published together or not at all,
 * and both are validated against the one schema on the version above.
 */
@Entity
@Table(name = "document_template_body")
@IdClass(TemplateBodyId.class)
public class DocumentTemplateBodyEntity {

    @Id
    @Column(name = "template_id", length = 36)
    private String templateId;

    @Id
    @Column(name = "version_no")
    private Integer versionNo;

    @Id
    @Column(name = "locale", length = 16)
    private String locale;

    @Column(name = "blob_sha256", nullable = false, length = 64)
    private String blobSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private DocumentFormat format;

    protected DocumentTemplateBodyEntity() {
    }

    public DocumentTemplateBodyEntity(String templateId, int versionNo, String locale,
            String blobSha256, DocumentFormat format) {
        this.templateId = templateId;
        this.versionNo = Integer.valueOf(versionNo);
        this.locale = locale;
        this.blobSha256 = blobSha256;
        this.format = format;
    }

    public String templateId() {
        return templateId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String locale() {
        return locale;
    }

    public String blobSha256() {
        return blobSha256;
    }

    public DocumentFormat format() {
        return format;
    }
}
