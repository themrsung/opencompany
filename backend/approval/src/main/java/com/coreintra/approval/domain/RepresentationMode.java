package com.coreintra.approval.domain;

import java.io.Serializable;

/**
 * How a company's 대표이사 authority is exercised.
 *
 * <p>A company-level setting with effective dating, and it governs
 * <b>every</b> representative-level approval in the system — 취업규칙 changes,
 * temporary master issuance, and any document whose line reaches a
 * representative step. It is not a per-document choice, because that would let
 * the person drafting a document pick how much authority it needs.
 */
public final class RepresentationMode implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 각자대표 — any one representative's approval is sufficient and final. */
        SEVERAL,
        /**
         * 공동대표 — joint representation. A configurable quorum: all designated
         * representatives, or N of M. A document is not approved until quorum is
         * met, and partial approval is a distinct, visible state rather than
         * something that looks like "still waiting".
         */
        JOINT
    }

    private final Kind kind;
    private final int requiredApprovals;
    private final int designatedRepresentatives;

    private RepresentationMode(Kind kind, int requiredApprovals, int designatedRepresentatives) {
        this.kind = kind;
        this.requiredApprovals = requiredApprovals;
        this.designatedRepresentatives = designatedRepresentatives;
    }

    /** 각자대표: one representative suffices. */
    public static RepresentationMode several(int designatedRepresentatives) {
        if (designatedRepresentatives < 1) {
            throw new IllegalArgumentException("a company must designate at least one representative");
        }
        return new RepresentationMode(Kind.SEVERAL, 1, designatedRepresentatives);
    }

    /**
     * 공동대표 with an explicit quorum.
     *
     * @throws IllegalArgumentException if the quorum is 1, or exceeds the number
     *         of representatives
     */
    public static RepresentationMode joint(int requiredApprovals, int designatedRepresentatives) {
        if (designatedRepresentatives < 2) {
            throw new IllegalArgumentException(
                    "joint representation needs at least two designated representatives, got "
                            + designatedRepresentatives);
        }
        if (requiredApprovals < 2) {
            // A "joint" mode satisfiable by one person is 각자대표 wearing a
            // different label, and it would quietly defeat the invariant the
            // acceptance tests exist to protect.
            throw new IllegalArgumentException(
                    "joint representation requires at least two approvals; a quorum of "
                            + requiredApprovals + " would be satisfiable by a single "
                            + "representative, which is 각자대표 under another name");
        }
        if (requiredApprovals > designatedRepresentatives) {
            throw new IllegalArgumentException(
                    "quorum of " + requiredApprovals + " cannot be met by "
                            + designatedRepresentatives + " representatives");
        }
        return new RepresentationMode(Kind.JOINT, requiredApprovals, designatedRepresentatives);
    }

    /** All designated representatives must approve. */
    public static RepresentationMode jointAll(int designatedRepresentatives) {
        return joint(designatedRepresentatives, designatedRepresentatives);
    }

    public Kind kind() {
        return kind;
    }

    /** How many representative approvals are needed. Always >= 2 under JOINT. */
    public int requiredApprovals() {
        return requiredApprovals;
    }

    public int designatedRepresentatives() {
        return designatedRepresentatives;
    }

    public boolean isJoint() {
        return kind == Kind.JOINT;
    }

    /** True when {@code approvals} distinct representatives satisfy this mode. */
    public boolean isSatisfiedBy(int distinctRepresentativeApprovals) {
        return distinctRepresentativeApprovals >= requiredApprovals;
    }

    @Override
    public String toString() {
        return kind == Kind.SEVERAL
                ? "각자대표 (any 1 of " + designatedRepresentatives + ")"
                : "공동대표 (" + requiredApprovals + " of " + designatedRepresentatives + ")";
    }
}
