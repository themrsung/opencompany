package com.coreintra.approval.service;

import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.compat.Immutables;
import java.util.List;

/**
 * One person's 결재함, in the three lists the screen actually shows.
 *
 * <p>The counts are carried alongside rather than left to the caller to compute,
 * because the badge on the navigation is drawn before the lists are and would
 * otherwise cost a second round trip to get a number the first one already knew.
 */
public final class ApprovalInbox {

    private final String accountId;
    private final List<ApprovalDocumentEntity> awaitingMe;
    private final List<ApprovalDocumentEntity> draftedByMe;
    private final List<ApprovalDocumentEntity> copiedToMe;
    private final int returnedToMeCount;
    private final int inProgressByMeCount;

    ApprovalInbox(String accountId, List<ApprovalDocumentEntity> awaitingMe,
            List<ApprovalDocumentEntity> draftedByMe, List<ApprovalDocumentEntity> copiedToMe,
            int returnedToMeCount, int inProgressByMeCount) {
        this.accountId = accountId;
        this.awaitingMe = Immutables.copyOf(awaitingMe);
        this.draftedByMe = Immutables.copyOf(draftedByMe);
        this.copiedToMe = Immutables.copyOf(copiedToMe);
        this.returnedToMeCount = returnedToMeCount;
        this.inProgressByMeCount = inProgressByMeCount;
    }

    public String accountId() {
        return accountId;
    }

    /** 결재 대기 — waiting on me right now, oldest first. The number that matters. */
    public List<ApprovalDocumentEntity> awaitingMe() {
        return awaitingMe;
    }

    /** 내가 올린 문서 — drafted by me, newest first. */
    public List<ApprovalDocumentEntity> draftedByMe() {
        return draftedByMe;
    }

    /** 참조 문서 — copied to me. No action required, and the UI must not imply any. */
    public List<ApprovalDocumentEntity> copiedToMe() {
        return copiedToMe;
    }

    /** The badge. */
    public int awaitingCount() {
        return awaitingMe.size();
    }

    /** 반려된 내 문서 — mine that came back and need attention. */
    public int returnedToMeCount() {
        return returnedToMeCount;
    }

    /** Mine still moving through their line. */
    public int inProgressByMeCount() {
        return inProgressByMeCount;
    }

    public boolean isEmpty() {
        return awaitingMe.isEmpty() && draftedByMe.isEmpty() && copiedToMe.isEmpty();
    }
}
