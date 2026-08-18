package com.coreintra.attendance.domain;

import java.io.Serializable;
import java.time.Duration;

/**
 * What a status <em>does</em>, as opposed to what it is called.
 *
 * <p>The built-in statuses — 근무, 재택, 외근, 자리비움, 휴가, 퇴근 — are seeded
 * rows carrying these same flags, not an enum. A client who needs 교육, 출장,
 * 병가 or 육아휴직 defines them with the behaviour they need, and the system
 * treats them identically to the built-ins.
 *
 * <p>That matters because the alternative — an enum with an {@code OTHER} case —
 * makes every custom status a second-class citizen that reports wrong, and
 * clients work around it by misusing a built-in that happens to behave right.
 */
public final class StatusBehaviour implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean countsAsWorking;
    private final boolean requiresApproval;
    private final boolean deductsLeaveBalance;
    private final boolean visibleToPeers;
    private final Duration autoExpiresAfter;

    private StatusBehaviour(Builder builder) {
        this.countsAsWorking = builder.countsAsWorking;
        this.requiresApproval = builder.requiresApproval;
        this.deductsLeaveBalance = builder.deductsLeaveBalance;
        this.visibleToPeers = builder.visibleToPeers;
        this.autoExpiresAfter = builder.autoExpiresAfter;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Counts toward worked hours in reporting. 재택 and 외근 do; 자리비움 does not. */
    public boolean countsAsWorking() {
        return countsAsWorking;
    }

    /** Entering this status raises a 결재 document rather than taking effect at once. */
    public boolean requiresApproval() {
        return requiresApproval;
    }

    /**
     * Approving this status writes a debit to the leave ledger.
     *
     * <p>Implies {@link #requiresApproval()} — see {@link Builder#build()}. A
     * status that silently spent someone's 연차 without an approval step would
     * be indistinguishable from a bug, and users would not trust their balance.
     */
    public boolean deductsLeaveBalance() {
        return deductsLeaveBalance;
    }

    /**
     * Colleagues can see this status on the who's-in view.
     *
     * <p>False for statuses a client considers private — 병가 in some
     * organisations. Peers then see only that the person is unavailable.
     */
    public boolean visibleToPeers() {
        return visibleToPeers;
    }

    /**
     * Ends the status automatically after this long, or null to persist.
     *
     * <p>자리비움 that nobody clears is the reason this exists: a who's-in board
     * full of stale "away" markers is a board people stop reading.
     */
    public Duration autoExpiresAfter() {
        return autoExpiresAfter;
    }

    public boolean expiresAutomatically() {
        return autoExpiresAfter != null;
    }

    public static final class Builder {
        private boolean countsAsWorking;
        private boolean requiresApproval;
        private boolean deductsLeaveBalance;
        private boolean visibleToPeers = true;
        private Duration autoExpiresAfter;

        public Builder countsAsWorking(boolean value) {
            this.countsAsWorking = value;
            return this;
        }

        public Builder requiresApproval(boolean value) {
            this.requiresApproval = value;
            return this;
        }

        public Builder deductsLeaveBalance(boolean value) {
            this.deductsLeaveBalance = value;
            return this;
        }

        public Builder visibleToPeers(boolean value) {
            this.visibleToPeers = value;
            return this;
        }

        public Builder autoExpiresAfter(Duration value) {
            this.autoExpiresAfter = value;
            return this;
        }

        public StatusBehaviour build() {
            if (deductsLeaveBalance && !requiresApproval) {
                throw new IllegalArgumentException(
                        "a status that deducts leave must also require approval; otherwise it "
                                + "spends someone's balance with nobody having agreed to it");
            }
            if (autoExpiresAfter != null && autoExpiresAfter.isNegative()) {
                throw new IllegalArgumentException("autoExpiresAfter cannot be negative");
            }
            return new StatusBehaviour(this);
        }
    }
}
