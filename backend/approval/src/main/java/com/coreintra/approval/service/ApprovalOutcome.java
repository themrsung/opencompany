package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalAction;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.businesstime.BusinessInstant;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * What a document ended up as, handed to whatever has to act on it.
 *
 * <p>A value rather than a database view on purpose. Listeners run <em>before</em>
 * the approval is written (see {@link ApprovalOutcomeListener}), so there is
 * nothing to read yet; everything a listener could legitimately need is here.
 */
public final class ApprovalOutcome implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String documentId;
    private final String companyId;
    private final String documentType;
    private final String title;
    private final String drafterAccountId;
    private final String drafterOrgUnitId;
    private final BigDecimal amount;
    private final String currencyCode;
    private final ApprovalState state;
    private final ApprovalAction finalAction;
    private final String actorAccountId;
    private final BusinessInstant decidedAt;
    private final String snapshotHash;

    ApprovalOutcome(ApprovalDocumentEntity document, ApprovalState state,
            ApprovalAction finalAction, String actorAccountId, BusinessInstant decidedAt,
            String snapshotHash) {
        this.documentId = document.id();
        this.companyId = document.companyId();
        this.documentType = document.documentType();
        this.title = document.title();
        this.drafterAccountId = document.drafterAccountId();
        this.drafterOrgUnitId = document.drafterOrgUnitId();
        this.amount = document.amount();
        this.currencyCode = document.currencyCode();
        this.state = state;
        this.finalAction = finalAction;
        this.actorAccountId = actorAccountId;
        this.decidedAt = decidedAt;
        this.snapshotHash = snapshotHash;
    }

    public String documentId() {
        return documentId;
    }

    public String companyId() {
        return companyId;
    }

    /** The type the client registered, e.g. {@code LEAVE_REQUEST}. Listeners filter on it. */
    public String documentType() {
        return documentType;
    }

    public String title() {
        return title;
    }

    public String drafterAccountId() {
        return drafterAccountId;
    }

    public String drafterOrgUnitId() {
        return drafterOrgUnitId;
    }

    /** Null for a document with no money on it. */
    public BigDecimal amount() {
        return amount;
    }

    public String currencyCode() {
        return currencyCode;
    }

    /** {@link ApprovalState#APPROVED}, {@code RETURNED} or {@code RECALLED}. */
    public ApprovalState state() {
        return state;
    }

    /** 승인 or 전결 for an approval; 반려 or 회수 otherwise. */
    public ApprovalAction finalAction() {
        return finalAction;
    }

    /** Who took the deciding action — for 대결, the acting person, not the absentee. */
    public String actorAccountId() {
        return actorAccountId;
    }

    /** Business time, not UTC. */
    public BusinessInstant decidedAt() {
        return decidedAt;
    }

    /** The digest the deciding action was recorded against. */
    public String snapshotHash() {
        return snapshotHash;
    }

    @Override
    public String toString() {
        return documentType + " " + documentId + " → " + state + " (" + finalAction + ")";
    }
}
