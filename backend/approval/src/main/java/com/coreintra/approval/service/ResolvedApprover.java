package com.coreintra.approval.service;

import java.io.Serializable;

/**
 * A person a role expression resolved to, with the labels they held at that
 * moment.
 *
 * <p>The rank label and display name are carried alongside the account id
 * because they are <em>snapshotted</em>. A 결재란 printed three years later must
 * show 부장 홍길동 if that is who signed, not 대표 홍길동 because he was promoted
 * since. Re-deriving them at render time would quietly rewrite the document.
 */
public final class ResolvedApprover implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String accountId;
    private final String displayName;
    private final String rankLabel;
    private final int seniority;

    public ResolvedApprover(String accountId, String displayName, String rankLabel, int seniority) {
        if (accountId == null) {
            throw new NullPointerException("accountId");
        }
        this.accountId = accountId;
        this.displayName = displayName;
        this.rankLabel = rankLabel;
        this.seniority = seniority;
    }

    public String accountId() {
        return accountId;
    }

    public String displayName() {
        return displayName;
    }

    /** 직급 as of resolution, e.g. 부장. Null when the person holds no rank. */
    public String rankLabel() {
        return rankLabel;
    }

    /**
     * Rank seniority as of resolution.
     *
     * <p>Only used to order a step's approvers so the snapshot is stable and a
     * 결재란 prints seniors first. Never used to decide who may act — that is the
     * resolved set, in full.
     */
    public int seniority() {
        return seniority;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ResolvedApprover)) {
            return false;
        }
        return accountId.equals(((ResolvedApprover) obj).accountId);
    }

    @Override
    public int hashCode() {
        return accountId.hashCode();
    }

    @Override
    public String toString() {
        return (rankLabel == null ? "" : rankLabel + " ") + displayName + " (" + accountId + ")";
    }
}
