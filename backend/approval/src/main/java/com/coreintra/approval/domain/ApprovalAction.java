package com.coreintra.approval.domain;

/**
 * What an approver can do at their step.
 *
 * <p>The two Korean-specific ones — 전결 and 대결 — are not variations on
 * "approve". They change who the approval is attributed to and what happens to
 * the remaining steps, and conflating them with 승인 loses exactly the
 * information an auditor is looking for.
 */
public enum ApprovalAction {

    /** 승인 — approve, and pass to the next step. */
    APPROVE(false),

    /**
     * 반려 — return to the drafter, with a mandatory reason.
     *
     * <p>The reason is mandatory because a document that comes back with no
     * explanation is re-submitted unchanged, and the whole loop repeats.
     */
    RETURN(true),

    /** 보류 — hold. The document stays at this step, visibly waiting on this person. */
    HOLD(true),

    /**
     * 전결 — delegated final approval: a lower rank approves finally, where
     * policy allows it, and the remaining 결재 steps are skipped rather than
     * silently auto-approved.
     *
     * <p>Recorded distinctly so the trail shows the 부장 finalised what would
     * normally have gone to the 대표, which is precisely what a reviewer needs
     * to see.
     */
    DELEGATED_FINAL(true),

    /**
     * 대결 — acting approval on behalf of an absent approver.
     *
     * <p>Routed automatically from the absentee's status, and attributed to the
     * acting person while recording whom they acted for. It is not the same as
     * the absent person having approved, and the trail must never suggest it was.
     */
    ACTING(true),

    /**
     * 회수 — the drafter recalls the document.
     *
     * <p>Permitted only before the first approval. After someone has approved,
     * withdrawing it would erase a decision that was really made.
     */
    RECALL(false);

    private final boolean reasonRequired;

    ApprovalAction(boolean reasonRequired) {
        this.reasonRequired = reasonRequired;
    }

    /** True when the action is refused without a non-blank reason. */
    public boolean isReasonRequired() {
        return reasonRequired;
    }
}
