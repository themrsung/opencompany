package com.coreintra.attendance.domain;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A leave balance, kept as a ledger rather than a number.
 *
 * <h2>Why a ledger</h2>
 *
 * <p>A stored balance answers "how many days are left" and nothing else. When
 * an employee disputes it — and they will, because leave is money — a single
 * number cannot say where it came from. Every grant, use, carry-over, expiry
 * and correction is a row here, and the balance is their sum. The same reason
 * accounting uses a journal.
 *
 * <p>Nothing is ever amended in place. A mistaken transaction is corrected by
 * posting its reverse with a reason, so the record shows both the error and the
 * fix.
 *
 * <h2>Days are BigDecimal</h2>
 *
 * <p>Half-days and quarter-days are ordinary in Korean practice, and 0.25 is
 * not representable in binary floating point. A balance that drifts by
 * 0.0000001 of a day over a year is a support ticket that cannot be explained.
 */
public final class LeaveLedger implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Why a transaction exists. Every entry says which. */
    public enum TransactionKind {
        /** 연차 발생 — an accrual or annual grant from policy. */
        GRANT(true),
        /** 이월 — carried over from the previous period. */
        CARRY_OVER(true),
        /** 사용 — leave taken, written when a 휴가 request is approved. */
        USE(false),
        /** 소멸 — unused balance expiring under policy. */
        EXPIRY(false),
        /** 조정 — a manual correction, always with a reason and an actor. */
        ADJUSTMENT(true),
        /** 취소 — reversal of a previously used leave, e.g. an approval recalled. */
        CANCELLATION(true);

        private final boolean increasesBalance;

        TransactionKind(boolean increasesBalance) {
            this.increasesBalance = increasesBalance;
        }

        /** True when the natural sign of this kind is positive. */
        public boolean increasesBalance() {
            return increasesBalance;
        }
    }

    /** One immutable row. */
    public static final class Transaction implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String id;
        private final TransactionKind kind;
        private final BigDecimal days;
        private final BusinessInstant occurredAt;
        private final LocalDate effectiveFrom;
        private final LocalDate expiresOn;
        private final String reason;
        private final String sourceDocumentId;
        private final String actorAccountId;

        public Transaction(String id, TransactionKind kind, BigDecimal days,
                BusinessInstant occurredAt, LocalDate effectiveFrom, LocalDate expiresOn,
                String reason, String sourceDocumentId, String actorAccountId) {
            if (days == null) {
                throw new NullPointerException("days");
            }
            if (days.signum() <= 0) {
                // Sign comes from the kind, never from the caller. A negative
                // GRANT or a positive USE would make the ledger unreadable and
                // let a bug silently invert an employee's balance.
                throw new IllegalArgumentException(
                        "days must be a positive magnitude; the direction comes from the kind. "
                                + "Got " + days + " for " + kind);
            }
            if (kind == TransactionKind.ADJUSTMENT && (reason == null || reason.trim().isEmpty())) {
                throw new IllegalArgumentException(
                        "a manual adjustment must state a reason; an unexplained change to "
                                + "someone's leave balance is indistinguishable from a bug");
            }
            this.id = id;
            this.kind = kind;
            this.days = days;
            this.occurredAt = occurredAt;
            this.effectiveFrom = effectiveFrom;
            this.expiresOn = expiresOn;
            this.reason = reason;
            this.sourceDocumentId = sourceDocumentId;
            this.actorAccountId = actorAccountId;
        }

        public String id() {
            return id;
        }

        public TransactionKind kind() {
            return kind;
        }

        /** Always positive. Direction comes from {@link #kind()}. */
        public BigDecimal days() {
            return days;
        }

        /** Signed contribution to the balance. */
        public BigDecimal signedDays() {
            return kind.increasesBalance() ? days : days.negate();
        }

        public BusinessInstant occurredAt() {
            return occurredAt;
        }

        /** When this grant becomes usable. Null means immediately. */
        public LocalDate effectiveFrom() {
            return effectiveFrom;
        }

        /** When an unused grant lapses. Null means it does not. */
        public LocalDate expiresOn() {
            return expiresOn;
        }

        public String reason() {
            return reason;
        }

        /** The 휴가신청서 this came from, where applicable. */
        public String sourceDocumentId() {
            return sourceDocumentId;
        }

        public String actorAccountId() {
            return actorAccountId;
        }
    }

    private final String employeeId;
    private final List<Transaction> transactions;

    public LeaveLedger(String employeeId, List<Transaction> transactions) {
        this.employeeId = employeeId;
        List<Transaction> ordered = new ArrayList<Transaction>(transactions);
        Collections.sort(ordered, new Comparator<Transaction>() {
            @Override
            public int compare(Transaction left, Transaction right) {
                // Business ordering: date first, then offset (ADR 0002).
                return BusinessInstant.COMPARATOR.compare(left.occurredAt(), right.occurredAt());
            }
        });
        this.transactions = Immutables.copyOf(ordered);
    }

    public String employeeId() {
        return employeeId;
    }

    public List<Transaction> transactions() {
        return transactions;
    }

    /**
     * Balance as of a date: every transaction that has occurred and become
     * effective by then, less anything that has already expired.
     *
     * <p>A pure function over the rows. Nothing is cached and nothing is stored,
     * so the balance cannot drift from the transactions that explain it.
     */
    public BigDecimal balanceOn(LocalDate asOf) {
        BigDecimal balance = BigDecimal.ZERO;
        for (Transaction transaction : transactions) {
            if (transaction.occurredAt().businessDate().isAfter(asOf)) {
                continue;
            }
            if (transaction.effectiveFrom() != null && transaction.effectiveFrom().isAfter(asOf)) {
                // Granted but not yet usable — next year's 연차 booked in advance.
                continue;
            }
            balance = balance.add(transaction.signedDays());
        }
        return balance;
    }

    /** Days used within a period. For the "used this year" figure. */
    public BigDecimal usedBetween(LocalDate from, LocalDate to) {
        BigDecimal used = BigDecimal.ZERO;
        for (Transaction transaction : transactions) {
            if (transaction.kind() != TransactionKind.USE) {
                continue;
            }
            LocalDate on = transaction.occurredAt().businessDate();
            if (!on.isBefore(from) && !on.isAfter(to)) {
                used = used.add(transaction.days());
            }
        }
        return used;
    }

    /**
     * Grants that will lapse on or before {@code by} and are still unspent.
     *
     * <p>Drives the "your 연차 expires soon" reminder. Computed against the
     * balance rather than per-grant, because leave is spent from a pool and
     * matching a use back to the grant it came from is a fiction.
     */
    public List<Transaction> expiringBy(LocalDate by, LocalDate asOf) {
        List<Transaction> expiring = new ArrayList<Transaction>();
        if (balanceOn(asOf).signum() <= 0) {
            return Immutables.copyOf(expiring);
        }
        for (Transaction transaction : transactions) {
            if (!transaction.kind().increasesBalance() || transaction.expiresOn() == null) {
                continue;
            }
            if (!transaction.expiresOn().isAfter(by) && !transaction.expiresOn().isBefore(asOf)) {
                expiring.add(transaction);
            }
        }
        return Immutables.copyOf(expiring);
    }

    /** True when {@code days} can be taken as of the date without going negative. */
    public boolean canAfford(BigDecimal days, LocalDate asOf) {
        return balanceOn(asOf).compareTo(days) >= 0;
    }
}
