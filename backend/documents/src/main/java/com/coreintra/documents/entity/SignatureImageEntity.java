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
import javax.persistence.Table;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;

/**
 * An enrolled 도장 or 서명 image, per employee (brief 6.3).
 *
 * <p>The bytes are never served to a browser. They are composited into a render
 * server-side and nowhere else, because an image URL that returns a person's seal is
 * a forgery kit with an access log. This row is the record that one was enrolled;
 * {@link SignatureImpressionUseEntity} is the record of every time it was used.
 *
 * <p>Revocation is a stamp here, not a DELETE, so a document rendered last year still
 * explains which seal it carried.
 */
@Entity
@Table(name = "signature_image")
public class SignatureImageEntity {

    /** 도장 or 서명. Both are images; only the word for them differs. */
    public enum Kind {

        /** 도장 - a stamp, usually round, usually the company's or the officer's. */
        SEAL,

        /** 서명 - a handwritten signature. */
        SIGNATURE
    }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "employee_id", nullable = false, length = 36)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "blob_sha256", nullable = false, length = 64)
    private String blobSha256;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "enrolled_by_account_id", nullable = false, length = 36)
    private String enrolledByAccountId;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "enrolled_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "enrolled_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "enrolled_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable enrolledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by_account_id", length = 36)
    private String revokedByAccountId;

    @Column(name = "revoked_reason")
    private String revokedReason;

    protected SignatureImageEntity() {
    }

    public SignatureImageEntity(String id, String companyId, String employeeId, Kind kind,
            String blobSha256, String contentType, String enrolledByAccountId,
            BusinessInstant enrolledAt) {
        this.id = id;
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.kind = kind;
        this.blobSha256 = blobSha256;
        this.contentType = contentType;
        this.enrolledByAccountId = enrolledByAccountId;
        this.enrolledAt = BusinessInstantEmbeddable.from(enrolledAt);
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Withdraws the seal from future renders.
     *
     * @throws IllegalArgumentException if no reason is given - a revocation with no reason
     *         is a mystery in an audit six months later, and this is the table where
     *         mysteries matter most
     */
    public void revoke(String byAccountId, String reason, OffsetDateTime at) {
        if (Texts.isBlank(reason)) {
            throw new IllegalArgumentException("a seal is revoked for a reason; record it");
        }
        if (Texts.isBlank(byAccountId)) {
            throw new IllegalArgumentException("a revocation is made by someone; record them");
        }
        this.revokedReason = reason;
        this.revokedByAccountId = byAccountId;
        this.revokedAt = at;
    }

    // There is deliberately no accessor for blobSha256. A getter here would be the one
    // line between an audited render and an <img src> pointing at somebody's seal, and it
    // would be added by accident during a refactor. The field is read by exactly one JPQL
    // projection - SignatureImageRepository#liveContentHashForRenderer - which the
    // compositor uses and nothing else does.

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String employeeId() {
        return employeeId;
    }

    public Kind kind() {
        return kind;
    }

    public String contentType() {
        return contentType;
    }

    public String enrolledByAccountId() {
        return enrolledByAccountId;
    }

    public BusinessInstant enrolledAt() {
        return enrolledAt == null ? null : enrolledAt.toBusinessInstant();
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public String revokedByAccountId() {
        return revokedByAccountId;
    }

    public String revokedReason() {
        return revokedReason;
    }

    public boolean isLive() {
        return revokedAt == null;
    }
}
