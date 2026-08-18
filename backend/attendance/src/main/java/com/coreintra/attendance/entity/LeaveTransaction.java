package com.coreintra.attendance.entity;

import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.businesstime.BusinessInstant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One row of the leave ledger. Append-only: never updated, never deleted.
 *
 * <p>A mistake in someone's balance is corrected by posting its reverse, so the
 * record shows both the error and the fix. Leave is money to the person holding
 * it, and when they dispute the number — and they will — only the rows can say
 * where it came from. There are therefore no setters beyond construction.
 *
 * <p>{@code days} is always a positive magnitude. The direction comes from the
 * kind, so no bug can invert a balance by flipping a sign, and the database
 * enforces the same thing independently.
 *
 * <h2>Why the business instant is two plain columns</h2>
 *
 * <p>{@code leave_transaction} (V5) carries {@code occurred_business_date} and
 * {@code occurred_offset_seconds} but no generated {@code occurred_absolute_ts},
 * which {@code BusinessInstantEmbeddable} maps as a third, read-only column. The
 * embeddable is therefore not usable here without inventing a column the
 * migration does not have. The pair is converted through
 * {@link BusinessInstant#of} on the way out, which re-validates the 72-hour
 * window exactly as the embeddable would.
 */
@Entity
@Table(name = "leave_transaction")
public class LeaveTransaction {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "employee_id", nullable = false, length = 36)
    private String employeeId;

    /** Null only for a legacy row written before policies existed. */
    @Column(name = "policy_id", length = 36)
    private String policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private LeaveLedger.TransactionKind kind;

    @Column(name = "days", nullable = false, precision = 38, scale = 10)
    private BigDecimal days;

    @Column(name = "occurred_business_date", nullable = false)
    private LocalDate occurredBusinessDate;

    @Column(name = "occurred_offset_seconds", nullable = false)
    private int occurredOffsetSeconds;

    /** Null = usable immediately. Set for next year's grant booked in advance. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** Null = does not lapse. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "reason")
    private String reason;

    /** The 결재 document that authorised this. The idempotency key for an approval. */
    @Column(name = "source_document_id", length = 36)
    private String sourceDocumentId;

    @Column(name = "actor_account_id", length = 36)
    private String actorAccountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LeaveTransaction() {
    }

    public LeaveTransaction(String employeeId, String policyId,
            LeaveLedger.Transaction transaction) {
        this.id = transaction.id();
        this.employeeId = employeeId;
        this.policyId = policyId;
        this.kind = transaction.kind();
        this.days = transaction.days();
        this.occurredBusinessDate = transaction.occurredAt().businessDate();
        this.occurredOffsetSeconds = transaction.occurredAt().offsetSeconds();
        this.effectiveFrom = transaction.effectiveFrom();
        this.expiresOn = transaction.expiresOn();
        this.reason = transaction.reason();
        this.sourceDocumentId = transaction.sourceDocumentId();
        this.actorAccountId = transaction.actorAccountId();
        this.createdAt = OffsetDateTime.now();
    }

    /** Rebuilds the domain row, re-validating the offset window on the way out. */
    public LeaveLedger.Transaction toTransaction() {
        return new LeaveLedger.Transaction(id, kind, days,
                BusinessInstant.of(occurredBusinessDate, occurredOffsetSeconds),
                effectiveFrom, expiresOn, reason, sourceDocumentId, actorAccountId);
    }

    public String id() {
        return id;
    }

    public String employeeId() {
        return employeeId;
    }

    public String policyId() {
        return policyId;
    }

    public LeaveLedger.TransactionKind kind() {
        return kind;
    }

    public BigDecimal days() {
        return days;
    }

    public LocalDate occurredBusinessDate() {
        return occurredBusinessDate;
    }

    public int occurredOffsetSeconds() {
        return occurredOffsetSeconds;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate expiresOn() {
        return expiresOn;
    }

    public String reason() {
        return reason;
    }

    public String sourceDocumentId() {
        return sourceDocumentId;
    }

    public String actorAccountId() {
        return actorAccountId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
