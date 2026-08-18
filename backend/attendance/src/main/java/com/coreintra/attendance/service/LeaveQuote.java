package com.coreintra.attendance.service;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * What a 휴가 request would cost, worked out before anyone approves it.
 *
 * <p>Exists so the form can tell the drafter "this books 1.5 days and you have
 * 1" while they are still typing. The same arithmetic runs again inside
 * {@link LeaveService#recordApprovedLeave} and is authoritative there — this is
 * advice, not a reservation, and a colleague booking the same days in between
 * will change the answer.
 */
public final class LeaveQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    private final BigDecimal requestedDays;
    private final BigDecimal bookableDays;
    private final BigDecimal balanceDays;
    private final boolean affordable;

    LeaveQuote(BigDecimal requestedDays, BigDecimal bookableDays, BigDecimal balanceDays,
            boolean affordable) {
        this.requestedDays = requestedDays;
        this.bookableDays = bookableDays;
        this.balanceDays = balanceDays;
        this.affordable = affordable;
    }

    /** What was asked for. */
    public BigDecimal requestedDays() {
        return requestedDays;
    }

    /**
     * What it rounds to under the policy's minimum unit.
     *
     * <p>Rounded up, never down: booking less leave than is actually taken puts
     * the difference in the employee's favour in a way no policy intended, and
     * it accumulates.
     */
    public BigDecimal bookableDays() {
        return bookableDays;
    }

    public BigDecimal balanceDays() {
        return balanceDays;
    }

    /** True when the balance covers {@link #bookableDays()}. */
    public boolean isAffordable() {
        return affordable;
    }

    /** True when rounding changed the request, so the form can say so plainly. */
    public boolean isRounded() {
        return requestedDays.compareTo(bookableDays) != 0;
    }
}
