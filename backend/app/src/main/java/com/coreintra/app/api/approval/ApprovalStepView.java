package com.coreintra.app.api.approval;

import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.compat.Immutables;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * One 결재란 column: who was routed here, how many of them must sign, and how
 * many have.
 *
 * <h2>The approvers are the snapshot, not today's org chart</h2>
 *
 * <p>Steps resolve to a role expression — "the 부장 of the drafter's unit" — and
 * that expression is resolved to people once, at submission, and frozen onto the
 * document. Rendering it against the live org chart would show a document
 * routed to whoever holds the post now, which for anything in flight during a
 * reorg is a different person from the one whose signature is actually being
 * waited on.
 *
 * <p>{@link #getRemainingApprovals()} is what makes a 공동대표 quorum legible:
 * a document with two of three representative signatures is not "still waiting"
 * in the same sense as one nobody has looked at, and the screen has to be able
 * to say so.
 */
@Schema(name = "ApprovalStep", description = "One step of a resolved 결재선.")
public class ApprovalStepView {

    /**
     * One snapshotted approver, with the label they held at the time.
     *
     * <p>Named for the schema it becomes rather than shortened to a Java
     * convention. springdoc emits a bare {@code $ref} for a type that appears
     * only as an array item two levels down, and {@code DanglingSchemaCompleter}
     * fills that gap by simple class name — so renaming the schema away from the
     * class would leave a reference in the contract with nothing behind it, and
     * the generated client would type this as {@code unknown}.
     */
    public static class ApprovalStepApprover {
        private final String accountId;
        private final String displayName;
        private final String rankLabel;
        private final boolean acted;

        ApprovalStepApprover(String accountId, String displayName, String rankLabel,
                boolean acted) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.rankLabel = rankLabel;
            this.acted = acted;
        }

        public String getAccountId() {
            return accountId;
        }

        @Schema(description = "The name held at submission, so a later rename does not "
                + "rewrite a signature that has already been given.")
        public String getDisplayName() {
            return displayName;
        }

        @Schema(description = "The 직급 held at submission, e.g. 부장.")
        public String getRankLabel() {
            return rankLabel;
        }

        @Schema(description = "True once this person has recorded an action on this step.")
        public boolean isActed() {
            return acted;
        }
    }

    private final String id;
    private final int position;
    private final String kind;
    private final String roleExpression;
    private final String state;
    private final int requiredApprovals;
    private final int approvalsGiven;
    private final int remainingApprovals;
    private final boolean satisfied;
    private final boolean pending;
    private final List<ApprovalStepApprover> approvers;

    private ApprovalStepView(String id, int position, String kind, String roleExpression,
            String state, int requiredApprovals, int approvalsGiven, int remainingApprovals,
            boolean satisfied, boolean pending, List<ApprovalStepApprover> approvers) {
        this.id = id;
        this.position = position;
        this.kind = kind;
        this.roleExpression = roleExpression;
        this.state = state;
        this.requiredApprovals = requiredApprovals;
        this.approvalsGiven = approvalsGiven;
        this.remainingApprovals = remainingApprovals;
        this.satisfied = satisfied;
        this.pending = pending;
        this.approvers = Immutables.copyOf(approvers);
    }

    /**
     * @param snapshot the step's approvers as frozen at submission; the display
     *        names live there rather than on the domain step, which knows only
     *        account ids
     */
    static ApprovalStepView from(ApprovalStep step, List<ApprovalStepApproverEntity> snapshot) {
        List<ApprovalStepApprover> people = new ArrayList<ApprovalStepApprover>();
        for (ApprovalStepApproverEntity approver : snapshot) {
            people.add(new ApprovalStepApprover(approver.accountId(),
                    approver.resolvedDisplayName(),
                    approver.resolvedRankLabel(), hasActed(step, approver.accountId())));
        }
        return new ApprovalStepView(step.id(), step.position(), step.kind().name(),
                step.roleExpression(), step.state().name(), step.requiredApprovals(),
                step.distinctApprovals(), step.remainingApprovals(), step.isSatisfied(),
                step.isPending(), people);
    }

    private static boolean hasActed(ApprovalStep step, String accountId) {
        for (ApprovalStep.ApprovalActionRecord record : step.actions()) {
            if (accountId.equals(record.actorAccountId())
                    || accountId.equals(record.onBehalfOfAccountId())) {
                return true;
            }
        }
        return false;
    }

    public String getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    @Schema(description = "DRAFT (기안), REVIEW (검토), CONCURRENCE (합의), APPROVE (결재) "
            + "or CC (참조). CC needs no action and the UI must not imply one.",
            example = "APPROVE")
    public String getKind() {
        return kind;
    }

    @Schema(description = "The role this step was routed to before it was resolved to people.",
            example = "rank:bujang@DRAFTER_UNIT")
    public String getRoleExpression() {
        return roleExpression;
    }

    @Schema(description = "UPCOMING, PENDING, COMPLETED, RETURNED, HELD or SKIPPED. SKIPPED "
            + "means a 전결 finalised the document earlier and nobody signed here.",
            example = "PENDING")
    public String getState() {
        return state;
    }

    @Schema(description = "How many distinct approvals this step needs. Above one for a "
            + "공동대표 quorum or a parallel 합의.")
    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public int getApprovalsGiven() {
        return approvalsGiven;
    }

    @Schema(description = "Still outstanding. Non-zero on a PARTIALLY_APPROVED document is "
            + "the 공동대표 quorum waiting for the remaining representative.")
    public int getRemainingApprovals() {
        return remainingApprovals;
    }

    public boolean isSatisfied() {
        return satisfied;
    }

    public boolean isPending() {
        return pending;
    }

    @Schema(description = "Resolved once at submission and frozen, so a later reorg does "
            + "not change who a document in flight is waiting on.")
    public List<ApprovalStepApprover> getApprovers() {
        return approvers;
    }
}
