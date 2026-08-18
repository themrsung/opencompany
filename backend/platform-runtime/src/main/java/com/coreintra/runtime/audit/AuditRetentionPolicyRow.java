package com.coreintra.runtime.audit;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * How long one company's trail is kept.
 *
 * <p>The floor is five years and the number only ever grows (§12). Shortening a
 * retention is how a trail is destroyed without deleting anything, so the
 * direction is a constraint in the database and a refusal here, not a setting
 * with a warning next to it.
 */
@Entity
@Table(name = "audit_retention_policy")
public class AuditRetentionPolicyRow {

    /** Five years. Korean commercial books must be kept for this long. */
    public static final int FLOOR_DAYS = 1825;

    @Id
    @Column(name = "company_id", length = 36)
    private String companyId;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by_account_id", length = 36)
    private String updatedByAccountId;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected AuditRetentionPolicyRow() {
    }

    public AuditRetentionPolicyRow(String companyId, int retentionDays, OffsetDateTime updatedAt,
                                   String updatedByAccountId) {
        if (retentionDays < FLOOR_DAYS) {
            throw new IllegalArgumentException(
                    "audit retention starts at " + FLOOR_DAYS + " days; got " + retentionDays);
        }
        this.companyId = companyId;
        this.retentionDays = retentionDays;
        this.updatedAt = updatedAt;
        this.updatedByAccountId = updatedByAccountId;
    }

    public String companyId() {
        return companyId;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public String updatedByAccountId() {
        return updatedByAccountId;
    }

    /**
     * Upward only.
     *
     * @throws IllegalArgumentException if the new value is not larger. Equal is
     *         refused too: a no-op write on a retention policy is almost always
     *         a caller that meant to change something else.
     */
    void raiseTo(int newRetentionDays, OffsetDateTime at, String byAccountId) {
        if (newRetentionDays <= retentionDays) {
            throw new IllegalArgumentException(
                    "audit retention is configurable upward only: " + retentionDays + " -> "
                            + newRetentionDays + " days (§12)");
        }
        this.retentionDays = newRetentionDays;
        this.updatedAt = at;
        this.updatedByAccountId = byAccountId;
    }
}
