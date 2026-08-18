package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;

/**
 * A published version. Immutable: editing a template creates the next version.
 *
 * <p>The field manifest is stored <em>here</em> rather than on the template, because
 * a field added in v4 must not appear in a document drafted from v3. The version-compare
 * UI walks these rows, which is only possible while each one keeps its own schema.
 *
 * <p>{@code fieldSchema} is the encoded {@code DocumentFieldSchema}: tag, type, required
 * flag and both labels, one field per line. It is a snapshot, not a reference.
 */
@Entity
@Table(name = "document_template_version")
@IdClass(TemplateVersionId.class)
public class DocumentTemplateVersionEntity {

    @Id
    @Column(name = "template_id", length = 36)
    private String templateId;

    @Id
    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "field_schema", nullable = false)
    private String fieldSchema;

    /**
     * Whether the body carries a 결재란, detected at publish time.
     *
     * <p>Recorded rather than re-detected: "can this be submitted for 결재?" must be
     * answerable without unzipping the docx, and the approval module has no business
     * opening one.
     */
    @Column(name = "has_approval_block", nullable = false)
    private boolean hasApprovalBlock;

    @Column(name = "supersedes_version_no")
    private Integer supersedesVersionNo;

    @Column(name = "published_by_account_id", length = 36)
    private String publishedByAccountId;

    /** When the organisation says this was published. Ordering is business time, never UTC. */
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "published_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "published_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "published_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable publishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DocumentTemplateVersionEntity() {
    }

    public DocumentTemplateVersionEntity(String templateId, int versionNo, String fieldSchema,
            boolean hasApprovalBlock, Integer supersedesVersionNo, String publishedByAccountId,
            BusinessInstant publishedAt) {
        this.templateId = templateId;
        this.versionNo = Integer.valueOf(versionNo);
        this.fieldSchema = fieldSchema;
        this.hasApprovalBlock = hasApprovalBlock;
        this.supersedesVersionNo = supersedesVersionNo;
        this.publishedByAccountId = publishedByAccountId;
        this.publishedAt = BusinessInstantEmbeddable.from(publishedAt);
        this.createdAt = OffsetDateTime.now();
    }

    public String templateId() {
        return templateId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String fieldSchema() {
        return fieldSchema;
    }

    /** True when the body carries a 결재란 and the template can be submitted for approval. */
    public boolean hasApprovalBlock() {
        return hasApprovalBlock;
    }

    public Integer supersedesVersionNo() {
        return supersedesVersionNo;
    }

    public String publishedByAccountId() {
        return publishedByAccountId;
    }

    public BusinessInstant publishedAt() {
        return publishedAt == null ? null : publishedAt.toBusinessInstant();
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
