package com.coreintra.approval.domain;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A document's approval line and its state machine.
 *
 * <p>Everything that decides whether a document is approved lives here, as pure
 * domain logic with no persistence and no framework, so the invariants can be
 * tested as invariants.
 *
 * <h2>The rules, in one place</h2>
 *
 * <ul>
 *   <li>Steps advance in {@code position} order. Several steps may share a
 *       position — 합의 in parallel — and <em>all</em> of them must be satisfied
 *       before the line moves on.</li>
 *   <li>참조 steps never block and are completed the moment they are reached.</li>
 *   <li>반려 at any blocking step returns the document to the drafter, with a
 *       mandatory reason. Later steps are not consulted.</li>
 *   <li>회수 is permitted only before the first approval. Once someone has
 *       approved, recalling would erase a decision that was really made.</li>
 *   <li>전결 finalises the document immediately; every remaining step is marked
 *       SKIPPED rather than auto-approved, so the trail shows nobody signed
 *       them.</li>
 *   <li>Under 공동대표, a representative step needs a quorum of distinct
 *       representatives. One person cannot satisfy it, however many times they
 *       act.</li>
 * </ul>
 */
public final class ApprovalLine implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Refused transition. Carries a message suitable for the user. */
    public static class ApprovalRuleException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ApprovalRuleException(String message) {
            super(message);
        }
    }

    private final String documentId;
    private final String drafterAccountId;
    private final RepresentationMode representationMode;
    private final List<ApprovalStep> steps;

    private ApprovalState state = ApprovalState.DRAFTING;

    public ApprovalLine(String documentId, String drafterAccountId,
            RepresentationMode representationMode, List<ApprovalStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("an approval line needs at least one step");
        }
        List<ApprovalStep> ordered = new ArrayList<ApprovalStep>(steps);
        Collections.sort(ordered, new Comparator<ApprovalStep>() {
            @Override
            public int compare(ApprovalStep left, ApprovalStep right) {
                return Integer.compare(left.position(), right.position());
            }
        });
        boolean hasBlockingStep = false;
        for (ApprovalStep step : ordered) {
            if (step.kind().canBlock()) {
                hasBlockingStep = true;
            }
        }
        if (!hasBlockingStep) {
            // A line of nothing but 기안 and 참조 would complete the instant it
            // was submitted, which is a notification, not an approval.
            throw new IllegalArgumentException(
                    "an approval line needs at least one step that can approve or reject; "
                            + "a line of only 기안 and 참조 steps approves itself");
        }
        this.documentId = documentId;
        this.drafterAccountId = drafterAccountId;
        this.representationMode = representationMode;
        this.steps = ordered;
    }

    public String documentId() {
        return documentId;
    }

    public String drafterAccountId() {
        return drafterAccountId;
    }

    public RepresentationMode representationMode() {
        return representationMode;
    }

    public ApprovalState state() {
        return state;
    }

    public List<ApprovalStep> steps() {
        return Immutables.copyOf(steps);
    }

    /** Submits the document into the line. */
    public void submit() {
        if (state != ApprovalState.DRAFTING) {
            throw new ApprovalRuleException(
                    "This document has already been submitted. Its current state is " + state + ".");
        }
        state = ApprovalState.IN_PROGRESS;
        activateNextPosition();
    }

    /** Steps currently waiting for someone. Empty once terminal. */
    public List<ApprovalStep> pendingSteps() {
        List<ApprovalStep> pending = new ArrayList<ApprovalStep>();
        for (ApprovalStep step : steps) {
            if (step.isPending()) {
                pending.add(step);
            }
        }
        return pending;
    }

    /** True when this account is expected to act right now. */
    public boolean isAwaiting(String accountId) {
        for (ApprovalStep step : pendingSteps()) {
            if (step.mayAct(accountId) && !hasAlreadyApproved(step, accountId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records an action and advances the line.
     *
     * @throws ApprovalRuleException if the action is not permitted now
     */
    public void act(String stepId, String actorAccountId, String actorDisplayName,
            ApprovalAction action, BusinessInstant actedAt, String comment,
            String documentSnapshotHash, String onBehalfOfAccountId) {

        if (action == ApprovalAction.RECALL) {
            recall(actorAccountId, actorDisplayName, actedAt, comment, documentSnapshotHash);
            return;
        }
        if (state.isTerminal()) {
            throw new ApprovalRuleException(
                    "This document is " + state + " and can no longer be acted on.");
        }

        ApprovalStep step = requireStep(stepId);
        // The kind check comes first deliberately. A 참조 step is COMPLETED the
        // moment it is reached, so the generic "not waiting" guard below would
        // otherwise fire and tell a confused user about a state rather than
        // about the rule they actually ran into.
        if (!step.kind().requiresAction()) {
            throw new ApprovalRuleException(
                    "참조 steps are for information; they cannot approve or reject.");
        }
        if (!step.isPending() && step.state() != ApprovalStep.StepState.HELD) {
            throw new ApprovalRuleException(
                    "This step is not waiting for action; its state is " + step.state() + ".");
        }
        if (!step.mayAct(actorAccountId)) {
            throw new ApprovalRuleException(
                    "This step is not assigned to you. It was resolved to "
                            + step.eligibleApproverIds().size() + " approver(s) at submission.");
        }
        if (hasAlreadyApproved(step, actorAccountId)) {
            // The quorum guard. Without it one representative could satisfy a
            // 공동대표 step by acting twice, which is the invariant this whole
            // class exists to protect.
            throw new ApprovalRuleException(
                    "You have already approved this step. A joint-representation quorum needs "
                            + "distinct representatives.");
        }

        step.record(new ApprovalStep.ApprovalActionRecord(actorAccountId, actorDisplayName, action,
                actedAt, comment, documentSnapshotHash, onBehalfOfAccountId));

        switch (action) {
            case RETURN:
                step.markReturned();
                state = ApprovalState.RETURNED;
                return;

            case HOLD:
                step.markHeld();
                state = ApprovalState.ON_HOLD;
                return;

            case DELEGATED_FINAL:
                // 전결: finalise now, and mark everything left as SKIPPED rather
                // than approved. Nobody signed those steps.
                step.markCompleted();
                for (ApprovalStep remaining : steps) {
                    remaining.markSkipped();
                }
                state = ApprovalState.APPROVED;
                return;

            case APPROVE:
            case ACTING:
                if (state == ApprovalState.ON_HOLD) {
                    step.resumeFromHold();
                    state = ApprovalState.IN_PROGRESS;
                }
                if (step.isSatisfied()) {
                    step.markCompleted();
                    advance();
                } else {
                    // Quorum not yet met. Visibly partial, not "still waiting".
                    state = ApprovalState.PARTIALLY_APPROVED;
                }
                return;

            default:
                throw new ApprovalRuleException("Unhandled action " + action);
        }
    }

    /**
     * 회수 — the drafter withdraws the document.
     *
     * @throws ApprovalRuleException if anyone has already approved
     */
    public void recall(String actorAccountId, String actorDisplayName, BusinessInstant actedAt,
            String comment, String documentSnapshotHash) {
        if (!drafterAccountId.equals(actorAccountId)) {
            throw new ApprovalRuleException("Only the drafter may recall a document.");
        }
        if (state.isTerminal()) {
            throw new ApprovalRuleException(
                    "This document is " + state + " and can no longer be recalled.");
        }
        if (hasAnyApproval()) {
            throw new ApprovalRuleException(
                    "This document has already been approved by someone and can no longer be "
                            + "recalled. Ask an approver to return it instead, so the decision "
                            + "that was made stays on the record.");
        }
        ApprovalStep draftStep = steps.get(0);
        draftStep.record(new ApprovalStep.ApprovalActionRecord(actorAccountId, actorDisplayName,
                ApprovalAction.RECALL, actedAt, comment, documentSnapshotHash, null));
        for (ApprovalStep step : steps) {
            step.markSkipped();
        }
        state = ApprovalState.RECALLED;
    }

    /** True when any approving action has been recorded anywhere in the line. */
    public boolean hasAnyApproval() {
        for (ApprovalStep step : steps) {
            if (step.distinctApprovals() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAlreadyApproved(ApprovalStep step, String accountId) {
        for (ApprovalStep.ApprovalActionRecord record : step.actions()) {
            boolean approving = record.action() == ApprovalAction.APPROVE
                    || record.action() == ApprovalAction.DELEGATED_FINAL
                    || record.action() == ApprovalAction.ACTING;
            if (approving && record.actorAccountId().equals(accountId)) {
                return true;
            }
        }
        return false;
    }

    /** Moves to the next position once every step at the current one is satisfied. */
    private void advance() {
        for (ApprovalStep step : steps) {
            if (step.isPending() || step.state() == ApprovalStep.StepState.HELD) {
                // Something at this position is still outstanding - a parallel
                // 합의 peer. The line does not move.
                state = ApprovalState.IN_PROGRESS;
                return;
            }
        }
        if (!activateNextPosition()) {
            state = ApprovalState.APPROVED;
        } else {
            state = ApprovalState.IN_PROGRESS;
        }
    }

    /**
     * Activates every step at the next unstarted position.
     *
     * @return false when there is nothing left to activate
     */
    private boolean activateNextPosition() {
        int nextPosition = Integer.MAX_VALUE;
        for (ApprovalStep step : steps) {
            if (step.state() == ApprovalStep.StepState.UPCOMING && step.position() < nextPosition) {
                nextPosition = step.position();
            }
        }
        if (nextPosition == Integer.MAX_VALUE) {
            return false;
        }
        boolean activatedBlocking = false;
        for (ApprovalStep step : steps) {
            if (step.position() == nextPosition
                    && step.state() == ApprovalStep.StepState.UPCOMING) {
                step.activate();
                if (step.kind().requiresAction()) {
                    activatedBlocking = true;
                }
            }
        }
        // A position of nothing but 참조 completes instantly; keep going rather
        // than parking the document on steps that will never act.
        return activatedBlocking || activateNextPosition();
    }

    private ApprovalStep requireStep(String stepId) {
        for (ApprovalStep step : steps) {
            if (step.id().equals(stepId)) {
                return step;
            }
        }
        throw new ApprovalRuleException("No step " + stepId + " in this approval line.");
    }

    /** The full trail, in the order actions were taken. */
    public List<ApprovalStep.ApprovalActionRecord> trail() {
        List<ApprovalStep.ApprovalActionRecord> all =
                new ArrayList<ApprovalStep.ApprovalActionRecord>();
        for (ApprovalStep step : steps) {
            all.addAll(step.actions());
        }
        Collections.sort(all, new Comparator<ApprovalStep.ApprovalActionRecord>() {
            @Override
            public int compare(ApprovalStep.ApprovalActionRecord left,
                    ApprovalStep.ApprovalActionRecord right) {
                // Business ordering: date first, then offset. Never the wire string.
                return BusinessInstant.COMPARATOR.compare(left.actedAt(), right.actedAt());
            }
        });
        return Immutables.copyOf(all);
    }

    /** A short human summary for the inbox list. */
    public String summary() {
        if (state.isTerminal()) {
            return state.name();
        }
        List<ApprovalStep> pending = pendingSteps();
        if (pending.isEmpty()) {
            return state.name();
        }
        ApprovalStep first = pending.get(0);
        String detail = first.remainingApprovals() > 1
                ? " (" + first.remainingApprovals() + " more approvals needed)"
                : "";
        return state.name() + " at " + first.kind()
                + (Texts.isBlank(first.roleExpression()) ? "" : " — " + first.roleExpression())
                + detail;
    }
}
