package com.coreintra.approval.entity;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One version of a company's 취업규칙.
 *
 * <p>Never updated. An amendment is the next version with a later
 * {@code effectiveFrom}, and a repeal is a version too, because employees must
 * be able to read the text that applied on any past date — which is exactly the
 * date an in-place edit destroys.
 *
 * <h2>Why the approval's state is copied here</h2>
 *
 * <p>{@code approvalDocumentState} is redundant to read and load-bearing to
 * write. It is half of a composite foreign key into
 * {@code approval_document (id, state)}, and the migration constrains it to
 * {@code APPROVED}, so a row naming an approval that has not completed has
 * nothing to reference. That makes "no create, amend or repeal without 대표자
 * 결재" a property of the schema rather than a check some future code path can
 * forget — which matters here more than anywhere else in the system, because §4
 * says no admin, no master account and no API path may bypass it.
 *
 * <p>The representation mode is copied for the same reason it is effective-dated
 * elsewhere: a company that later switches 각자대표 → 공동대표 has not
 * retroactively invalidated rules enacted under the old arrangement, and the row
 * must be able to say which arrangement that was.
 */
@Entity
@Table(name = "employment_rules")
public class EmploymentRulesEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "approval_document_id", nullable = false, length = 36)
    private String approvalDocumentId;

    /** Always {@code APPROVED}; the database will not accept anything else. */
    @Column(name = "approval_document_state", nullable = false, length = 24)
    private String approvalDocumentState;

    @Column(name = "approved_under_mode", nullable = false, length = 16)
    private String approvedUnderMode;

    @Column(name = "approved_under_required_approvals", nullable = false)
    private int approvedUnderRequiredApprovals;

    @Column(name = "approved_under_designated", nullable = false)
    private int approvedUnderDesignated;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected EmploymentRulesEntity() {
    }

    public EmploymentRulesEntity(String id, String companyId, int version, LocalDate effectiveFrom,
            String approvalDocumentId, RepresentationMode approvedUnder) {
        this.id = id;
        this.companyId = companyId;
        this.version = version;
        this.effectiveFrom = effectiveFrom;
        this.approvalDocumentId = approvalDocumentId;
        this.approvalDocumentState = ApprovalState.APPROVED.name();
        this.approvedUnderMode = approvedUnder.kind().name();
        this.approvedUnderRequiredApprovals = approvedUnder.requiredApprovals();
        this.approvedUnderDesignated = approvedUnder.designatedRepresentatives();
        this.createdAt = OffsetDateTime.now();
    }

    /** The mode this version was enacted under, rebuilt through the domain factories. */
    public RepresentationMode approvedUnder() {
        if (RepresentationMode.Kind.JOINT.name().equals(approvedUnderMode)) {
            return RepresentationMode.joint(approvedUnderRequiredApprovals, approvedUnderDesignated);
        }
        return RepresentationMode.several(approvedUnderDesignated);
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public int version() {
        return version;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public String approvalDocumentId() {
        return approvalDocumentId;
    }

    public String approvalDocumentState() {
        return approvalDocumentState;
    }

    public String approvedUnderMode() {
        return approvedUnderMode;
    }

    public int approvedUnderRequiredApprovals() {
        return approvedUnderRequiredApprovals;
    }

    public int approvedUnderDesignated() {
        return approvedUnderDesignated;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
