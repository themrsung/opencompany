package com.coreintra.approval.entity;

import com.coreintra.approval.domain.ApprovalAction;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;
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
 * One recorded approval action. Append-only: never updated, never deleted.
 *
 * <p>This is the record of what was decided. An UPDATE here would be rewriting
 * history, so there are no setters beyond construction.
 */
@Entity
@Table(name = "approval_action")
public class ApprovalActionEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "step_id", nullable = false, length = 36)
    private String stepId;

    @Column(name = "actor_account_id", nullable = false, length = 36)
    private String actorAccountId;

    @Column(name = "actor_display_name", nullable = false, length = 200)
    private String actorDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 24)
    private ApprovalAction action;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "acted_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "acted_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "acted_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable actedAt;

    @Column(name = "comment")
    private String comment;

    /** SHA-256 of the document at this moment. Proves what was approved. */
    @Column(name = "document_snapshot_hash", nullable = false, length = 80)
    private String documentSnapshotHash;

    /** For 대결 only. Without it the trail would imply the absentee signed. */
    @Column(name = "on_behalf_of_account_id", length = 36)
    private String onBehalfOfAccountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ApprovalActionEntity() {
    }

    public ApprovalActionEntity(String id, String stepId, String actorAccountId,
            String actorDisplayName, ApprovalAction action, BusinessInstant actedAt, String comment,
            String documentSnapshotHash, String onBehalfOfAccountId) {
        this.id = id;
        this.stepId = stepId;
        this.actorAccountId = actorAccountId;
        this.actorDisplayName = actorDisplayName;
        this.action = action;
        this.actedAt = BusinessInstantEmbeddable.from(actedAt);
        this.comment = comment;
        this.documentSnapshotHash = documentSnapshotHash;
        this.onBehalfOfAccountId = onBehalfOfAccountId;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String stepId() {
        return stepId;
    }

    public String actorAccountId() {
        return actorAccountId;
    }

    public String actorDisplayName() {
        return actorDisplayName;
    }

    public ApprovalAction action() {
        return action;
    }

    public BusinessInstant actedAt() {
        return actedAt == null ? null : actedAt.toBusinessInstant();
    }

    public String comment() {
        return comment;
    }

    public String documentSnapshotHash() {
        return documentSnapshotHash;
    }

    public String onBehalfOfAccountId() {
        return onBehalfOfAccountId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
