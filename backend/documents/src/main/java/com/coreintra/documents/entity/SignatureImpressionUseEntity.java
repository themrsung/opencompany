package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Every occasion a signature image left the store. Append-only.
 *
 * <p>Without this table, "was my seal used on this?" has no answer. With it, the
 * question is a query. Note what it records: who <em>requested</em> the render, which
 * is not always who owns the seal - and those differ exactly when the table is worth
 * reading.
 */
@Entity
@Table(name = "signature_impression_use")
public class SignatureImpressionUseEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "signature_image_id", nullable = false, length = 36)
    private String signatureImageId;

    @Column(name = "document_id", length = 36)
    private String documentId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "requested_by_account_id", length = 36)
    private String requestedByAccountId;

    @Column(name = "purpose", nullable = false, length = 40)
    private String purpose;

    @Column(name = "used_at", nullable = false)
    private OffsetDateTime usedAt;

    protected SignatureImpressionUseEntity() {
    }

    public SignatureImpressionUseEntity(String id, String signatureImageId, String documentId,
            Integer versionNo, String requestedByAccountId, String purpose) {
        this.id = id;
        this.signatureImageId = signatureImageId;
        this.documentId = documentId;
        this.versionNo = versionNo;
        this.requestedByAccountId = requestedByAccountId;
        this.purpose = purpose;
        this.usedAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String signatureImageId() {
        return signatureImageId;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String requestedByAccountId() {
        return requestedByAccountId;
    }

    public String purpose() {
        return purpose;
    }

    public OffsetDateTime usedAt() {
        return usedAt;
    }
}
