package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.List;

/**
 * A document, its resolved line, and its trail — everything a 결재 screen shows.
 *
 * <p>Assembled in one place so a caller cannot accidentally render a document
 * beside a line loaded at a different moment. The line here is the rebuilt
 * domain object, so questions like "is this waiting on me?" are answered by the
 * state machine rather than by the reader re-deriving the rules.
 */
public final class ApprovalDocumentView {

    private final ApprovalDocumentEntity document;
    private final ApprovalLine line;
    private final List<ApprovalStepEntity> steps;
    private final List<ApprovalStepApproverEntity> approvers;
    private final List<ApprovalActionEntity> trail;

    ApprovalDocumentView(ApprovalDocumentEntity document, ApprovalLine line,
            List<ApprovalStepEntity> steps, List<ApprovalStepApproverEntity> approvers,
            List<ApprovalActionEntity> trail) {
        this.document = document;
        this.line = line;
        this.steps = Immutables.copyOf(steps);
        this.approvers = Immutables.copyOf(approvers);
        this.trail = Immutables.copyOf(trail);
    }

    public ApprovalDocumentEntity document() {
        return document;
    }

    /** The rebuilt state machine. Null only for a draft with no line yet. */
    public ApprovalLine line() {
        return line;
    }

    public ApprovalState state() {
        return document.state();
    }

    /** In position order. */
    public List<ApprovalStepEntity> steps() {
        return steps;
    }

    /** Snapshotted approvers across every step, with the labels they held. */
    public List<ApprovalStepApproverEntity> approvers() {
        return approvers;
    }

    /** The approvers of one step, for rendering a 결재란 column. */
    public List<ApprovalStepApproverEntity> approversOf(String stepId) {
        List<ApprovalStepApproverEntity> forStep = new ArrayList<ApprovalStepApproverEntity>();
        for (ApprovalStepApproverEntity approver : approvers) {
            if (approver.stepId().equals(stepId)) {
                forStep.add(approver);
            }
        }
        return Immutables.copyOf(forStep);
    }

    /** Append-only, in business-time order. */
    public List<ApprovalActionEntity> trail() {
        return trail;
    }

    /** True when this account is expected to act right now. */
    public boolean isAwaiting(String accountId) {
        return line != null && line.isAwaiting(accountId);
    }
}
