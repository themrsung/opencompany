package com.coreintra.approval.notify;

import com.coreintra.approval.domain.ApprovalAction;
import com.coreintra.businesstime.BusinessInstant;

/**
 * What happened, for whoever needs to hear about it.
 *
 * <p>Carries both Korean and English text: the recipient's locale decides which
 * is used, and a channel that cannot ask picks Korean, the installation default.
 */
public final class ApprovalNotification {

    public enum Kind {
        /** A document has arrived in your inbox. */
        AWAITING_YOUR_ACTION,
        /** Your document moved a step. */
        PROGRESSED,
        /** Your document was fully approved. */
        APPROVED,
        /** Your document came back, with a reason. */
        RETURNED,
        /** A document you are 참조 on was submitted. */
        FOR_YOUR_INFORMATION
    }

    private final Kind kind;
    private final String recipientAccountId;
    private final String documentId;
    private final String documentTitle;
    private final ApprovalAction triggeringAction;
    private final String actorDisplayName;
    private final String comment;
    private final BusinessInstant occurredAt;

    public ApprovalNotification(Kind kind, String recipientAccountId, String documentId,
            String documentTitle, ApprovalAction triggeringAction, String actorDisplayName,
            String comment, BusinessInstant occurredAt) {
        this.kind = kind;
        this.recipientAccountId = recipientAccountId;
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        this.triggeringAction = triggeringAction;
        this.actorDisplayName = actorDisplayName;
        this.comment = comment;
        this.occurredAt = occurredAt;
    }

    public Kind kind() {
        return kind;
    }

    public String recipientAccountId() {
        return recipientAccountId;
    }

    public String documentId() {
        return documentId;
    }

    public String documentTitle() {
        return documentTitle;
    }

    public ApprovalAction triggeringAction() {
        return triggeringAction;
    }

    public String actorDisplayName() {
        return actorDisplayName;
    }

    /** The 반려 reason, where there is one. */
    public String comment() {
        return comment;
    }

    public BusinessInstant occurredAt() {
        return occurredAt;
    }

    /** Korean subject line. -습니다 form throughout; these are read by 대표이사s. */
    public String subjectKo() {
        switch (kind) {
            case AWAITING_YOUR_ACTION:
                return "[결재 요청] " + documentTitle;
            case PROGRESSED:
                return "[결재 진행] " + documentTitle;
            case APPROVED:
                return "[결재 완료] " + documentTitle;
            case RETURNED:
                return "[반려] " + documentTitle;
            case FOR_YOUR_INFORMATION:
            default:
                return "[참조] " + documentTitle;
        }
    }

    public String subjectEn() {
        switch (kind) {
            case AWAITING_YOUR_ACTION:
                return "[Approval requested] " + documentTitle;
            case PROGRESSED:
                return "[In progress] " + documentTitle;
            case APPROVED:
                return "[Approved] " + documentTitle;
            case RETURNED:
                return "[Returned] " + documentTitle;
            case FOR_YOUR_INFORMATION:
            default:
                return "[FYI] " + documentTitle;
        }
    }

    public String bodyKo() {
        switch (kind) {
            case AWAITING_YOUR_ACTION:
                return "결재하실 문서가 도착하였습니다. 문서를 확인하신 뒤 처리해 주시기 바랍니다.";
            case PROGRESSED:
                return actorDisplayName + "님이 결재를 진행하였습니다.";
            case APPROVED:
                return "요청하신 문서의 결재가 모두 완료되었습니다.";
            case RETURNED:
                return actorDisplayName + "님이 문서를 반려하였습니다. 사유: "
                        + (comment == null ? "(사유 없음)" : comment);
            case FOR_YOUR_INFORMATION:
            default:
                return "참조로 지정된 문서가 상신되었습니다. 별도의 처리는 필요하지 않습니다.";
        }
    }

    public String bodyEn() {
        switch (kind) {
            case AWAITING_YOUR_ACTION:
                return "A document is waiting for your approval.";
            case PROGRESSED:
                return actorDisplayName + " approved this document.";
            case APPROVED:
                return "This document has been fully approved.";
            case RETURNED:
                return actorDisplayName + " returned this document. Reason: "
                        + (comment == null ? "(none given)" : comment);
            case FOR_YOUR_INFORMATION:
            default:
                return "You were copied on this document. No action is needed.";
        }
    }
}
