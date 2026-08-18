package com.coreintra.attendance.service;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A 휴가 request that has completed 결재, and the balance write it authorises.
 *
 * <p>{@code sourceDocumentId} is not optional and not decorative. It is the
 * authority for the deduction — leave is only ever spent by an approval — and it
 * is the idempotency key that stops a retried approval deducting twice.
 */
public final class ApprovedLeave implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String employeeId;
    private final String policyId;
    private final BigDecimal days;
    private final BusinessInstant occurredAt;
    private final String sourceDocumentId;
    private final String actorAccountId;
    private final String reason;

    public ApprovedLeave(String employeeId, String policyId, BigDecimal days,
            BusinessInstant occurredAt, String sourceDocumentId, String actorAccountId,
            String reason) {
        if (Texts.isBlank(employeeId)) {
            throw new IllegalArgumentException("employeeId");
        }
        if (Texts.isBlank(policyId)) {
            throw new IllegalArgumentException(
                    "policyId: which policy the days come out of decides the bookable unit and "
                            + "the expiry, so it cannot be inferred");
        }
        if (days == null || days.signum() <= 0) {
            throw new IllegalArgumentException(
                    "days must be a positive magnitude; the direction comes from the transaction "
                            + "kind, never from the caller's sign");
        }
        if (occurredAt == null) {
            throw new NullPointerException("occurredAt");
        }
        if (Texts.isBlank(sourceDocumentId)) {
            throw new IllegalArgumentException(
                    "휴가는 결재된 문서로만 차감할 수 있습니다. (Leave is only ever deducted on the authority "
                            + "of an approved 결재 document. A deduction with no document behind it "
                            + "is indistinguishable from a bug, and the employee has no way to "
                            + "find out where their days went.)");
        }
        this.employeeId = employeeId;
        this.policyId = policyId;
        this.days = days;
        this.occurredAt = occurredAt;
        this.sourceDocumentId = sourceDocumentId;
        this.actorAccountId = actorAccountId;
        this.reason = reason;
    }

    public String employeeId() {
        return employeeId;
    }

    public String policyId() {
        return policyId;
    }

    public BigDecimal days() {
        return days;
    }

    /** Business time. The day the leave is booked against, not the day it was clicked. */
    public BusinessInstant occurredAt() {
        return occurredAt;
    }

    public String sourceDocumentId() {
        return sourceDocumentId;
    }

    /** Whoever gave the final approval, for the trail. */
    public String actorAccountId() {
        return actorAccountId;
    }

    public String reason() {
        return reason;
    }
}
