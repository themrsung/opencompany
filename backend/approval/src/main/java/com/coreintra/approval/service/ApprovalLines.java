package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.businesstime.BusinessInstant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds a live {@link ApprovalLine} from what is stored.
 *
 * <h2>By replaying the trail, not by loading a state field</h2>
 *
 * <p>The step states in {@code approval_step} are a projection: they exist so
 * the inbox query can filter in SQL. The truth is {@code approval_action}, which
 * is append-only. Rebuilding by replay means the in-memory line is always
 * derived from the immutable record, and it means the state machine's own rules
 * are re-run every time rather than trusted from a column somebody could have
 * updated.
 *
 * <p>That gives a free integrity check, and {@link #rebuild} takes it: if
 * replaying the trail does not land on the state the document claims, something
 * wrote history behind the domain's back. That is not a condition to carry on
 * from — it means the next action would be authorised against a state nobody
 * derived — so it fails loudly and names both states.
 */
public final class ApprovalLines {

    /** Stored state and replayed state disagree. A corrupted document, refused. */
    public static class TrailMismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public TrailMismatchException(String message) {
            super(message);
        }
    }

    private ApprovalLines() {
    }

    /**
     * The line as it stands now.
     *
     * @param mode the representation mode the document was submitted under —
     *        never today's, or a company that switched to 공동대표 last week would
     *        retroactively invalidate approvals given under 각자대표
     */
    public static ApprovalLine rebuild(ApprovalDocumentEntity document,
            List<ApprovalStepEntity> steps, List<ApprovalStepApproverEntity> approvers,
            List<ApprovalActionEntity> actions, RepresentationMode mode) {

        Map<String, Set<String>> eligibleByStep = eligibleByStep(approvers);
        List<ApprovalStep> domainSteps = new ArrayList<ApprovalStep>();
        for (ApprovalStepEntity step : steps) {
            Set<String> eligible = eligibleByStep.get(step.id());
            if (eligible == null || eligible.isEmpty()) {
                throw new TrailMismatchException(
                        "step " + step.id() + " of document " + document.id() + " has no "
                                + "snapshotted approvers. The resolution recorded at submission is "
                                + "the only thing that says who may act, so this document cannot "
                                + "be acted on.");
            }
            domainSteps.add(new ApprovalStep(step.id(), step.position(), step.kind(),
                    step.roleExpression(), eligible, step.requiredApprovals()));
        }

        ApprovalLine line = new ApprovalLine(document.id(), document.drafterAccountId(), mode,
                domainSteps);
        if (document.state() == ApprovalState.DRAFTING && actions.isEmpty()) {
            return line;
        }
        line.submit();
        for (ApprovalActionEntity action : inTrailOrder(actions)) {
            line.act(action.stepId(), action.actorAccountId(), action.actorDisplayName(),
                    action.action(), action.actedAt(), action.comment(),
                    action.documentSnapshotHash(), action.onBehalfOfAccountId());
        }
        if (line.state() != document.state()) {
            throw new TrailMismatchException(
                    "document " + document.id() + " is stored as " + document.state()
                            + ", but replaying its " + actions.size() + " recorded action(s) "
                            + "produces " + line.state() + ". The trail is the record; a stored "
                            + "state that disagrees with it was not written by the state machine.");
        }
        return line;
    }

    /**
     * Business ordering, then the UTC clock as a tiebreaker.
     *
     * <p>Two actions can share a business instant — an approver clicking twice
     * in the same second, or a batch. Replaying them in the wrong order would
     * produce a different line, so the tiebreak is {@code created_at}, the
     * machine's record of the order they were actually recorded in. Never the
     * derived absolute timestamp, which reorders a {@code 26:00} action before
     * the next day's {@code -02:00} one (ADR 0002).
     */
    private static List<ApprovalActionEntity> inTrailOrder(List<ApprovalActionEntity> actions) {
        List<ApprovalActionEntity> ordered = new ArrayList<ApprovalActionEntity>(actions);
        Collections.sort(ordered, new Comparator<ApprovalActionEntity>() {
            @Override
            public int compare(ApprovalActionEntity left, ApprovalActionEntity right) {
                int byBusinessTime = BusinessInstant.COMPARATOR.compare(
                        left.actedAt(), right.actedAt());
                if (byBusinessTime != 0) {
                    return byBusinessTime;
                }
                if (left.createdAt() == null || right.createdAt() == null) {
                    return 0;
                }
                return left.createdAt().compareTo(right.createdAt());
            }
        });
        return ordered;
    }

    private static Map<String, Set<String>> eligibleByStep(
            List<ApprovalStepApproverEntity> approvers) {
        Map<String, Set<String>> byStep = new LinkedHashMap<String, Set<String>>();
        for (ApprovalStepApproverEntity approver : approvers) {
            Set<String> forStep = byStep.get(approver.stepId());
            if (forStep == null) {
                forStep = new LinkedHashSet<String>();
                byStep.put(approver.stepId(), forStep);
            }
            forStep.add(approver.accountId());
        }
        return byStep;
    }
}
