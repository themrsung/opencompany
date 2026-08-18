package com.coreintra.approval.entity;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * An approver resolved onto a step at submission time.
 *
 * <p>Rank label and display name are snapshotted alongside the account id, so a
 * 결재란 rendered years later still shows the rank the person held when they
 * signed, rather than the one they hold now.
 */
@Entity
@Table(name = "approval_step_approver")
@IdClass(ApprovalStepApproverEntity.Key.class)
public class ApprovalStepApproverEntity {

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private String stepId;
        private String accountId;

        public Key() {
        }

        public Key(String stepId, String accountId) {
            this.stepId = stepId;
            this.accountId = accountId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Key other = (Key) obj;
            return stepId.equals(other.stepId) && accountId.equals(other.accountId);
        }

        @Override
        public int hashCode() {
            return stepId.hashCode() * 31 + accountId.hashCode();
        }
    }

    @Id
    @Column(name = "step_id", length = 36)
    private String stepId;

    @Id
    @Column(name = "account_id", length = 36)
    private String accountId;

    /** The rank as it stood at submission. Not re-resolved later. */
    @Column(name = "resolved_rank_label", length = 100)
    private String resolvedRankLabel;

    @Column(name = "resolved_display_name", length = 200)
    private String resolvedDisplayName;

    protected ApprovalStepApproverEntity() {
    }

    public ApprovalStepApproverEntity(String stepId, String accountId, String resolvedRankLabel,
            String resolvedDisplayName) {
        this.stepId = stepId;
        this.accountId = accountId;
        this.resolvedRankLabel = resolvedRankLabel;
        this.resolvedDisplayName = resolvedDisplayName;
    }

    public String stepId() {
        return stepId;
    }

    public String accountId() {
        return accountId;
    }

    public String resolvedRankLabel() {
        return resolvedRankLabel;
    }

    public String resolvedDisplayName() {
        return resolvedDisplayName;
    }
}
