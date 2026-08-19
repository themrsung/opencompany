package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;

/**
 * One version's bytes. Append-only: no UPDATE, no DELETE, and no setters.
 *
 * <p>A correction is the next version naming this one as superseded, so the trail
 * shows both the mistake and the fix - the same rule the approval and leave ledgers
 * follow.
 *
 * <p>Authored-at is a business instant because a document authored at 26:30 belongs
 * to that business day, and its approval ordering depends on saying so. {@code createdAt}
 * is what the machine observed and is never conflated with it.
 */
@Entity
@Table(name = "document_version")
@IdClass(DocumentVersionId.class)
public class DocumentVersionEntity {

    @Id
    @Column(name = "document_id", length = 36)
    private String documentId;

    @Id
    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "blob_sha256", nullable = false, length = 64)
    private String blobSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private DocumentFormat format;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "authored_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "authored_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "authored_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable authoredAt;

    @Column(name = "author_account_id", nullable = false, length = 36)
    private String authorAccountId;

    @Column(name = "supersedes_version_no")
    private Integer supersedesVersionNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DocumentVersionEntity() {
    }

    public DocumentVersionEntity(String documentId, int versionNo, String blobSha256,
            DocumentFormat format, BusinessInstant authoredAt, String authorAccountId,
            Integer supersedesVersionNo) {
        this.documentId = documentId;
        this.versionNo = Integer.valueOf(versionNo);
        this.blobSha256 = blobSha256;
        this.format = format;
        this.authoredAt = BusinessInstantEmbeddable.from(authoredAt);
        this.authorAccountId = authorAccountId;
        this.supersedesVersionNo = supersedesVersionNo;
        this.createdAt = OffsetDateTime.now();
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String blobSha256() {
        return blobSha256;
    }

    public DocumentFormat format() {
        return format;
    }

    public BusinessInstant authoredAt() {
        return authoredAt == null ? null : authoredAt.toBusinessInstant();
    }

    public String authorAccountId() {
        return authorAccountId;
    }

    public Integer supersedesVersionNo() {
        return supersedesVersionNo;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
