package com.coreintra.approval.domain;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One position in a 결재선, with its resolved approvers and what they did.
 *
 * <h2>Resolved at submission, then frozen</h2>
 *
 * <p>A template step names a <em>role</em> — "the 부장 of the drafter's org
 * unit", "anyone with {@code finance.expense:approve} in company X". That
 * expression is evaluated once, at submission, and the resulting people are
 * snapshotted here. A reorganisation two weeks later does not reroute a
 * document that is already halfway through its line, and does not retroactively
 * change who was supposed to have signed it.
 *
 * <h2>Several approvers at one position</h2>
 *
 * <p>합의 steps can sit in parallel — all of them must agree. A representative
 * step under 공동대표 has several eligible approvers of whom a quorum must
 * sign. Both are expressed by {@link #eligibleApproverIds} plus
 * {@link #requiredApprovals}, so the advancing logic has one shape rather than
 * two special cases.
 */
public final class ApprovalStep implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What has happened at this step. */
    public enum StepState {
        /** Not yet reached. */
        UPCOMING,
        /** The line is here, waiting. */
        PENDING,
        /** Satisfied — enough approvals, or a CC that needed none. */
        COMPLETED,
        /** Rejected here. */
        RETURNED,
        /** Held by an approver. */
        HELD,
        /**
         * Never acted on because a 전결 finalised the document earlier.
         *
         * <p>Distinct from COMPLETED: nobody signed this, and the trail must not
         * imply that they did.
         */
        SKIPPED
    }

    private final String id;
    private final int position;
    private final ApprovalStepKind kind;
    private final String roleExpression;
    private final Set<String> eligibleApproverIds;
    private final int requiredApprovals;

    private StepState state = StepState.UPCOMING;
    private final List<ApprovalActionRecord> actions = new ArrayList<ApprovalActionRecord>();

    public ApprovalStep(String id, int position, ApprovalStepKind kind, String roleExpression,
            Set<String> eligibleApproverIds, int requiredApprovals) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (eligibleApproverIds == null || eligibleApproverIds.isEmpty()) {
            // An unresolvable role expression must fail loudly at submission
            // rather than produce a document that can never complete.
            throw new IllegalArgumentException(
                    "step " + position + " (" + kind + ") resolved to no approver. The role "
                            + "expression \"" + roleExpression + "\" matched nobody as of the "
                            + "submission date.");
        }
        if (requiredApprovals < 1) {
            throw new IllegalArgumentException("requiredApprovals must be at least 1");
        }
        if (requiredApprovals > eligibleApproverIds.size()) {
            throw new IllegalArgumentException(
                    "step " + position + " needs " + requiredApprovals + " approvals but resolved "
                            + "only " + eligibleApproverIds.size() + " eligible approver(s); this "
                            + "document could never complete");
        }
        this.id = id;
        this.position = position;
        this.kind = kind;
        this.roleExpression = roleExpression;
        this.eligibleApproverIds = Immutables.setCopyOf(eligibleApproverIds);
        this.requiredApprovals = requiredApprovals;
    }

    /** A single-approver step, the common case. */
    public static ApprovalStep single(String id, int position, ApprovalStepKind kind,
            String roleExpression, String approverId) {
        Set<String> one = new LinkedHashSet<String>();
        one.add(approverId);
        return new ApprovalStep(id, position, kind, roleExpression, one, 1);
    }

    public String id() {
        return id;
    }

    public int position() {
        return position;
    }

    public ApprovalStepKind kind() {
        return kind;
    }

    /** The expression this step was resolved from. Kept for the audit trail. */
    public String roleExpression() {
        return roleExpression;
    }

    public Set<String> eligibleApproverIds() {
        return eligibleApproverIds;
    }

    public int requiredApprovals() {
        return requiredApprovals;
    }

    public StepState state() {
        return state;
    }

    public List<ApprovalActionRecord> actions() {
        return Immutables.copyOf(actions);
    }

    public boolean isPending() {
        return state == StepState.PENDING;
    }

    public boolean mayAct(String accountId) {
        return eligibleApproverIds.contains(accountId);
    }

    /** Distinct people who have approved here — 승인, 전결 or 대결. */
    public int distinctApprovals() {
        Set<String> approvers = new LinkedHashSet<String>();
        for (ApprovalActionRecord record : actions) {
            if (record.action() == ApprovalAction.APPROVE
                    || record.action() == ApprovalAction.DELEGATED_FINAL
                    || record.action() == ApprovalAction.ACTING) {
                approvers.add(record.actorAccountId());
            }
        }
        return approvers.size();
    }

    /** True when enough distinct approvals have been recorded. */
    public boolean isSatisfied() {
        return distinctApprovals() >= requiredApprovals;
    }

    /** How many more approvals this step needs. Zero once satisfied. */
    public int remainingApprovals() {
        int outstanding = requiredApprovals - distinctApprovals();
        return outstanding < 0 ? 0 : outstanding;
    }

    void activate() {
        if (state == StepState.UPCOMING) {
            // 참조 never waits for anyone.
            state = kind.requiresAction() ? StepState.PENDING : StepState.COMPLETED;
        }
    }

    void markCompleted() {
        state = StepState.COMPLETED;
    }

    void markReturned() {
        state = StepState.RETURNED;
    }

    void markHeld() {
        state = StepState.HELD;
    }

    void markSkipped() {
        if (state == StepState.UPCOMING || state == StepState.PENDING) {
            state = StepState.SKIPPED;
        }
    }

    void resumeFromHold() {
        if (state == StepState.HELD) {
            state = StepState.PENDING;
        }
    }

    void record(ApprovalActionRecord record) {
        actions.add(record);
    }

    /** One recorded action: who, when, what, why, and against which document snapshot. */
    public static final class ApprovalActionRecord implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String actorAccountId;
        private final String actorDisplayName;
        private final ApprovalAction action;
        private final BusinessInstant actedAt;
        private final String comment;
        private final String documentSnapshotHash;
        private final String onBehalfOfAccountId;

        public ApprovalActionRecord(String actorAccountId, String actorDisplayName,
                ApprovalAction action, BusinessInstant actedAt, String comment,
                String documentSnapshotHash, String onBehalfOfAccountId) {
            if (action.isReasonRequired() && Texts.isBlank(comment)) {
                throw new IllegalArgumentException(
                        action + " requires a reason. A document that comes back with no "
                                + "explanation is re-submitted unchanged.");
            }
            if (action == ApprovalAction.ACTING && Texts.isBlank(onBehalfOfAccountId)) {
                throw new IllegalArgumentException(
                        "대결 must record whom it was performed on behalf of; otherwise the trail "
                                + "implies the absent approver signed personally");
            }
            this.actorAccountId = actorAccountId;
            this.actorDisplayName = actorDisplayName;
            this.action = action;
            this.actedAt = actedAt;
            this.comment = comment;
            this.documentSnapshotHash = documentSnapshotHash;
            this.onBehalfOfAccountId = onBehalfOfAccountId;
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

        /** Business time, not UTC. See ADR 0002. */
        public BusinessInstant actedAt() {
            return actedAt;
        }

        public String comment() {
            return comment;
        }

        /**
         * SHA-256 of the document as it stood at this moment.
         *
         * <p>What makes the trail meaningful: it proves what was approved, not
         * merely that something was.
         */
        public String documentSnapshotHash() {
            return documentSnapshotHash;
        }

        /** For 대결, the absent approver. Null otherwise. */
        public String onBehalfOfAccountId() {
            return onBehalfOfAccountId;
        }
    }
}
