package com.coreintra.approval.domain;

/**
 * The kinds of step in a 결재선.
 *
 * <p>These are not interchangeable labels. Each behaves differently when the
 * line advances, and the differences are the whole reason a Korean approval
 * line is not just an ordered list of approvers.
 */
public enum ApprovalStepKind {

    /** 기안 — the drafter. Always first, always exactly one, never "approves". */
    DRAFT(false, false),

    /**
     * 검토 — a reviewer. Must act before the line advances, but a 반려 here
     * returns the document like any other rejection.
     */
    REVIEW(true, true),

    /**
     * 합의 — concurrence. Can block, and several may sit at the same position
     * and run in parallel: every one of them must agree before the line moves
     * on. This is the step kind that makes an approval line a graph rather than
     * a queue.
     */
    CONCURRENCE(true, true),

    /** 결재 — approval proper. The signature that carries authority. */
    APPROVE(true, true),

    /**
     * 참조 — carbon copy. Notified, never blocks, and cannot approve or reject.
     *
     * <p>A 참조 that could block would be a 검토 with a misleading name, and
     * users would stop trusting the distinction.
     */
    CC(false, false);

    private final boolean requiresAction;
    private final boolean canBlock;

    ApprovalStepKind(boolean requiresAction, boolean canBlock) {
        this.requiresAction = requiresAction;
        this.canBlock = canBlock;
    }

    /** True when the line waits for this step. */
    public boolean requiresAction() {
        return requiresAction;
    }

    /** True when this step can reject and stop the document. */
    public boolean canBlock() {
        return canBlock;
    }
}
