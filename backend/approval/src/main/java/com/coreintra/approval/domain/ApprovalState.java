package com.coreintra.approval.domain;

/**
 * Where a document sits in its approval life.
 *
 * <p>{@link #PARTIALLY_APPROVED} is deliberately its own state rather than a
 * shade of {@link #IN_PROGRESS}: under 공동대표, a document that one
 * representative has signed is genuinely different from one nobody has touched,
 * and the person waiting on it needs to see which.
 */
public enum ApprovalState {

    /** 작성중 — being written. Not yet in anyone's inbox. */
    DRAFTING(false),

    /** 진행중 — submitted and moving through the line. */
    IN_PROGRESS(false),

    /**
     * 일부승인 — under 공동대표, some but not all required representatives have
     * approved. Visibly distinct, never mistaken for "approved".
     */
    PARTIALLY_APPROVED(false),

    /** 보류 — held at a step by an approver, pending something. */
    ON_HOLD(false),

    /** 완결 — fully approved. Terminal. */
    APPROVED(true),

    /** 반려 — returned to the drafter with a reason. Terminal for this submission. */
    RETURNED(true),

    /** 회수 — recalled by the drafter before any approval. Terminal. */
    RECALLED(true);

    private final boolean terminal;

    ApprovalState(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further action can change this document's outcome. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when the document is somewhere in the line, awaiting action. */
    public boolean isActive() {
        return this == IN_PROGRESS || this == PARTIALLY_APPROVED || this == ON_HOLD;
    }
}
