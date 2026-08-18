package com.coreintra.approval.entity;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;
import java.math.BigDecimal;
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

/**
 * A document moving through 결재.
 *
 * <p>The amount is {@link BigDecimal} because it drives threshold rules: a
 * rounding error here changes who is required to sign, which is a governance
 * failure rather than a display bug (ADR 0004).
 */
@Entity
@Table(name = "approval_document")
public class ApprovalDocumentEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "drafter_account_id", nullable = false, length = 36)
    private String drafterAccountId;

    @Column(name = "drafter_org_unit_id", length = 36)
    private String drafterOrgUnitId;

    @Column(name = "template_id", length = 36)
    private String templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private ApprovalState state = ApprovalState.DRAFTING;

    @Column(name = "amount", precision = 38, scale = 10)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 12)
    private String currencyCode;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "submitted_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "submitted_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "submitted_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable submittedAt;

    /** Frozen at submission. The submitted artefact is immutable. */
    @Column(name = "submitted_snapshot_hash", length = 80)
    private String submittedSnapshotHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ApprovalDocumentEntity() {
    }

    public ApprovalDocumentEntity(String id, String companyId, String documentType, String title,
            String drafterAccountId) {
        this.id = id;
        this.companyId = companyId;
        this.documentType = documentType;
        this.title = title;
        this.drafterAccountId = drafterAccountId;
        this.createdAt = OffsetDateTime.now();
    }

    public void markSubmitted(BusinessInstant at, String snapshotHash) {
        this.submittedAt = BusinessInstantEmbeddable.from(at);
        this.submittedSnapshotHash = snapshotHash;
        this.state = ApprovalState.IN_PROGRESS;
    }

    public void setState(ApprovalState value) {
        this.state = value;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String documentType() {
        return documentType;
    }

    public String title() {
        return title;
    }

    public void setTitle(String value) {
        this.title = value;
    }

    public String drafterAccountId() {
        return drafterAccountId;
    }

    public String drafterOrgUnitId() {
        return drafterOrgUnitId;
    }

    public void setDrafterOrgUnitId(String value) {
        this.drafterOrgUnitId = value;
    }

    public String templateId() {
        return templateId;
    }

    public void setTemplateId(String value) {
        this.templateId = value;
    }

    public ApprovalState state() {
        return state;
    }

    public BigDecimal amount() {
        return amount;
    }

    public void setAmount(BigDecimal value) {
        this.amount = value;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String value) {
        this.currencyCode = value;
    }

    public BusinessInstant submittedAt() {
        return submittedAt == null ? null : submittedAt.toBusinessInstant();
    }

    public String submittedSnapshotHash() {
        return submittedSnapshotHash;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
