package com.coreintra.app.api.approval;

import com.coreintra.approval.entity.ApprovalActionEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry in the immutable trail.
 *
 * <p>Everything §4 asks a trail to record: who, when in business time, what they
 * did, why, and the digest of the document as it stood at that moment. The
 * digest is what makes a signature mean something — without it "박부장 approved"
 * is a claim about an artefact that may since have changed.
 *
 * <p>{@link #getOnBehalfOfAccountId()} is never folded into the actor. A 대결
 * reads "김대리 acted for 박부장", and collapsing the two into "박부장 approved"
 * would be a false statement about who signed.
 */
@Schema(name = "ApprovalAction", description = "One immutable entry in a document's trail.")
public class ApprovalActionView {

    private final String id;
    private final String stepId;
    private final String actorAccountId;
    private final String actorDisplayName;
    private final String action;
    private final String actedAt;
    private final String comment;
    private final String documentSnapshotHash;
    private final String onBehalfOfAccountId;
    private final String recordedAt;

    private ApprovalActionView(ApprovalActionEntity entity) {
        this.id = entity.id();
        this.stepId = entity.stepId();
        this.actorAccountId = entity.actorAccountId();
        this.actorDisplayName = entity.actorDisplayName();
        this.action = entity.action().name();
        this.actedAt = ApiWire.wire(entity.actedAt());
        this.comment = entity.comment();
        this.documentSnapshotHash = entity.documentSnapshotHash();
        this.onBehalfOfAccountId = entity.onBehalfOfAccountId();
        this.recordedAt = ApiWire.utc(entity.createdAt());
    }

    static ApprovalActionView from(ApprovalActionEntity entity) {
        return new ApprovalActionView(entity);
    }

    public String getId() {
        return id;
    }

    @Schema(description = "Null for a 회수, which is an act on the document rather than at "
            + "any one step.")
    public String getStepId() {
        return stepId;
    }

    public String getActorAccountId() {
        return actorAccountId;
    }

    @Schema(description = "The name as it stood when they acted.")
    public String getActorDisplayName() {
        return actorDisplayName;
    }

    @Schema(description = "APPROVE (승인), RETURN (반려), HOLD (보류), DELEGATED_FINAL (전결), "
            + "ACTING (대결) or RECALL (회수).", example = "APPROVE")
    public String getAction() {
        return action;
    }

    @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ", no timezone. "
            + "The trail is ordered by business date first and then by offset, so a 26:00 "
            + "action precedes the next day's -02:00 one.",
            example = ApiWire.INSTANT_EXAMPLE)
    public String getActedAt() {
        return actedAt;
    }

    @Schema(description = "Mandatory on 반려, 보류, 전결 and 대결; optional on 승인.")
    public String getComment() {
        return comment;
    }

    @Schema(description = "The digest of the document at the moment it was signed.")
    public String getDocumentSnapshotHash() {
        return documentSnapshotHash;
    }

    @Schema(description = "Set only on a 대결: the absent approver whose place was taken. "
            + "The actor is still the person who signed.")
    public String getOnBehalfOfAccountId() {
        return onBehalfOfAccountId;
    }

    public String getRecordedAt() {
        return recordedAt;
    }
}
