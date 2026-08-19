package com.coreintra.approval.entity;

import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.domain.ApprovalStepKind;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/** One position in a document's resolved 결재선. */
@Entity
@Table(name = "approval_step")
public class ApprovalStepEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ApprovalStepKind kind;

    /** The expression this was resolved from. Kept so the trail explains itself. */
    @Column(name = "role_expression", length = 300)
    private String roleExpression;

    /** Greater than 1 for a 공동대표 quorum or a parallel 합의 group. */
    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ApprovalStep.StepState state = ApprovalStep.StepState.UPCOMING;

    protected ApprovalStepEntity() {
    }

    public ApprovalStepEntity(String id, String documentId, int position, ApprovalStepKind kind,
            String roleExpression, int requiredApprovals) {
        this.id = id;
        this.documentId = documentId;
        this.position = position;
        this.kind = kind;
        this.roleExpression = roleExpression;
        this.requiredApprovals = requiredApprovals;
    }

    public String id() {
        return id;
    }

    public String documentId() {
        return documentId;
    }

    public int position() {
        return position;
    }

    public ApprovalStepKind kind() {
        return kind;
    }

    public String roleExpression() {
        return roleExpression;
    }

    public int requiredApprovals() {
        return requiredApprovals;
    }

    public ApprovalStep.StepState state() {
        return state;
    }

    public void setState(ApprovalStep.StepState value) {
        this.state = value;
    }
}
