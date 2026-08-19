package com.coreintra.app.api.approval;

import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.service.ApprovalInbox;
import com.coreintra.compat.Immutables;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * 결재함 — the three lists and the badge counts, in one response.
 *
 * <h2>Why this is not three endpoints</h2>
 *
 * <p>§12 names the approval inbox one of the two screens that decide whether
 * people like this product. It is opened dozens of times a day, and the badge on
 * the navigation is drawn before the lists are. Three endpoints would mean three
 * round trips for one screen and a fourth for the badge, and the badge would be
 * a number the first request already knew. One query inside
 * {@code ApprovalInboxService} feeds all of it.
 *
 * <h2>Which document lands in which list</h2>
 *
 * <p>{@code draftedByMe} and {@code copiedToMe} are exclusive: being copied on
 * your own document is not news, so it appears only under the former.
 * {@code awaitingMe} is orthogonal — a document you drafted and are also an
 * approver on is genuinely both, and hiding it from either list would lose it.
 * The counts are of rows, not of distinct documents, for that reason.
 */
@Schema(name = "ApprovalInbox", description = "One person's 결재함 with its badge counts.")
public class ApprovalInboxResponse {

    /** The numbers the navigation badge needs before any list is rendered. */
    @Schema(name = "ApprovalInboxCounts")
    public static class Counts {
        private final int awaiting;
        private final int draftedByMe;
        private final int copiedToMe;
        private final int returnedToMe;
        private final int inProgressByMe;

        Counts(ApprovalInbox inbox) {
            this.awaiting = inbox.awaitingCount();
            this.draftedByMe = inbox.draftedByMe().size();
            this.copiedToMe = inbox.copiedToMe().size();
            this.returnedToMe = inbox.returnedToMeCount();
            this.inProgressByMe = inbox.inProgressByMeCount();
        }

        @Schema(description = "결재 대기 — waiting on me right now. The number that matters.")
        public int getAwaiting() {
            return awaiting;
        }

        @Schema(description = "내가 올린 문서 — drafted by me.")
        public int getDraftedByMe() {
            return draftedByMe;
        }

        @Schema(description = "참조 문서 — copied to me, no action required.")
        public int getCopiedToMe() {
            return copiedToMe;
        }

        @Schema(description = "반려된 내 문서 — mine that came back and need attention.")
        public int getReturnedToMe() {
            return returnedToMe;
        }

        @Schema(description = "Mine still moving through their line.")
        public int getInProgressByMe() {
            return inProgressByMe;
        }
    }

    private final String accountId;
    private final Counts counts;
    private final List<ApprovalDocumentSummary> awaitingMe;
    private final List<ApprovalDocumentSummary> draftedByMe;
    private final List<ApprovalDocumentSummary> copiedToMe;
    private final int listLimit;

    private ApprovalInboxResponse(String accountId, Counts counts,
            List<ApprovalDocumentSummary> awaitingMe, List<ApprovalDocumentSummary> draftedByMe,
            List<ApprovalDocumentSummary> copiedToMe, int listLimit) {
        this.accountId = accountId;
        this.counts = counts;
        this.awaitingMe = Immutables.copyOf(awaitingMe);
        this.draftedByMe = Immutables.copyOf(draftedByMe);
        this.copiedToMe = Immutables.copyOf(copiedToMe);
        this.listLimit = listLimit;
    }

    public static ApprovalInboxResponse from(ApprovalInbox inbox, int listLimit) {
        return new ApprovalInboxResponse(inbox.accountId(), new Counts(inbox),
                summaries(inbox.awaitingMe()), summaries(inbox.draftedByMe()),
                summaries(inbox.copiedToMe()), listLimit);
    }

    private static List<ApprovalDocumentSummary> summaries(List<ApprovalDocumentEntity> rows) {
        List<ApprovalDocumentSummary> views = new ArrayList<ApprovalDocumentSummary>();
        for (ApprovalDocumentEntity row : rows) {
            views.add(ApprovalDocumentSummary.from(row));
        }
        return views;
    }

    public String getAccountId() {
        return accountId;
    }

    public Counts getCounts() {
        return counts;
    }

    @Schema(description = "Oldest first: the thing that has been waiting longest is the "
            + "thing that is holding someone up.")
    public List<ApprovalDocumentSummary> getAwaitingMe() {
        return awaitingMe;
    }

    @Schema(description = "Newest first.")
    public List<ApprovalDocumentSummary> getDraftedByMe() {
        return draftedByMe;
    }

    @Schema(description = "Newest first. Never includes documents you drafted yourself.")
    public List<ApprovalDocumentSummary> getCopiedToMe() {
        return copiedToMe;
    }

    @Schema(description = "The cap applied to each list. A list at this length may have been "
            + "truncated; page the full collection through /approvals/drafts or filter the "
            + "inbox by company.")
    public int getListLimit() {
        return listLimit;
    }
}
