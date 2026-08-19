package com.coreintra.runtime.support;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One issued support session, as a row.
 *
 * <p>The domain object in {@code auth} decides whether a session may exist: the
 * typed company name, the representation quorum, the never-grantable list. This
 * records what was decided, and re-states the two invariants a row can carry on
 * its own - a bounded life, and no wildcard capabilities.
 *
 * <p>Nothing here is ever deleted. A session that ended is a session with
 * {@code revokedAt} set or an {@code expiresAt} in the past, and both remain
 * readable forever, because "there was no support session" and "the record of it
 * is gone" must not look the same.
 *
 * <h2>There is no extension</h2>
 *
 * <p>{@code expiresAt} is written once and has no setter. Wanting longer means a
 * new approval and a new reason, which means a new row. A method here that moved
 * the deadline would be used under exactly the time pressure §8 was written
 * about.
 */
@Entity
@Table(name = "temporary_master_grant")
public class TemporaryMasterGrantRow {

    /** Four hours unless the issuer says otherwise. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(4);

    /** The hard ceiling. Not configurable: §8 fixes it. */
    public static final Duration MAX_TTL = Duration.ofHours(24);

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    /** The TEMPORARY_MASTER user account the engineer signs in as. */
    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "issued_by_account_id", nullable = false, length = 36)
    private String issuedByAccountId;

    @Column(name = "engineer_name", nullable = false, length = 200)
    private String engineerName;

    /** Shown in the banner every user in the company sees, and in the trail. */
    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "ticket_reference", length = 120)
    private String ticketReference;

    /** Evidence that a person typed their own company's name out in full. */
    @Column(name = "typed_company_name", nullable = false, length = 200)
    private String typedCompanyName;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    /** Seconds, as an int, because the column is INTEGER and a day fits easily. */
    @Column(name = "time_to_live_seconds", nullable = false)
    private int timeToLiveSeconds;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Issuance is a business event as well as a machine one. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "businessDate", column = @Column(name = "issued_business_date")),
            @AttributeOverride(name = "offsetSeconds", column = @Column(name = "issued_offset_seconds")),
            @AttributeOverride(name = "absoluteTs", column = @Column(name = "issued_absolute_ts",
                    insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable issuedOn;

    @Column(name = "approval_document_id", nullable = false, length = 36)
    private String approvalDocumentId;

    /**
     * The company's representation mode at issuance, as a string.
     *
     * <p>Not the {@code RepresentationMode} type: that lives in {@code approval},
     * and this module does not depend on it. What is recorded is which mode
     * applied and how many representatives it required, which is what an auditor
     * reading this row years later needs. The mode object itself is the
     * approval module's business.
     */
    @Column(name = "representation_mode", nullable = false, length = 16)
    private String representationMode;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    /** §8: auditing is doubled, and it is not a per-session choice. */
    @Column(name = "audits_reads", nullable = false)
    private boolean auditsReads = true;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by_account_id", length = 36)
    private String revokedByAccountId;

    @Column(name = "session_report_id", length = 36)
    private String sessionReportId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Ticked one at a time.
     *
     * <p>A grant with no rows here can read nothing - not one row, not the
     * company's own name - and that is the default. Absence of rows is
     * meaningful rather than a half-written record.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "temporary_master_capability",
            joinColumns = @JoinColumn(name = "grant_id"))
    @Column(name = "capability", nullable = false, length = 120)
    private Set<String> capabilities = new LinkedHashSet<String>();

    /**
     * Who approved, named.
     *
     * <p>Under a joint quorum the count is only meaningful if the individuals
     * are distinct, so the set is of people rather than of approvals.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "temporary_master_approver",
            joinColumns = @JoinColumn(name = "grant_id"))
    private Set<TemporaryMasterApprover> approvers = new LinkedHashSet<TemporaryMasterApprover>();

    /** JPA requires a no-arg constructor. Not for application use. */
    protected TemporaryMasterGrantRow() {
    }

    TemporaryMasterGrantRow(String id, TemporaryMasterIssuance issuance, OffsetDateTime createdAt) {
        this.id = id;
        this.companyId = issuance.companyId();
        this.accountId = issuance.accountId();
        this.issuedByAccountId = issuance.issuedByAccountId();
        this.engineerName = issuance.engineerName();
        this.reason = issuance.reason();
        this.ticketReference = issuance.ticketReference();
        this.typedCompanyName = issuance.typedCompanyName();
        this.issuedAt = issuance.issuedAt();
        this.timeToLiveSeconds = (int) issuance.timeToLive().getSeconds();
        this.expiresAt = issuance.issuedAt().plus(issuance.timeToLive());
        this.issuedOn = BusinessInstantEmbeddable.from(issuance.issuedOn());
        this.approvalDocumentId = issuance.approvalDocumentId();
        this.representationMode = issuance.representationMode();
        this.requiredApprovals = issuance.requiredApprovals();
        this.auditsReads = true;
        this.createdAt = createdAt;
        this.capabilities = new LinkedHashSet<String>(issuance.capabilities());
        this.approvers = new LinkedHashSet<TemporaryMasterApprover>(issuance.approvers());
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String accountId() {
        return accountId;
    }

    public String issuedByAccountId() {
        return issuedByAccountId;
    }

    public String engineerName() {
        return engineerName;
    }

    public String reason() {
        return reason;
    }

    public String ticketReference() {
        return ticketReference;
    }

    public String typedCompanyName() {
        return typedCompanyName;
    }

    public OffsetDateTime issuedAt() {
        return issuedAt;
    }

    public Duration timeToLive() {
        return Duration.ofSeconds(timeToLiveSeconds);
    }

    /** Fixed at issue. There is no method that moves this. */
    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public BusinessInstant issuedOn() {
        return issuedOn == null ? null : issuedOn.toBusinessInstant();
    }

    public String approvalDocumentId() {
        return approvalDocumentId;
    }

    public String representationMode() {
        return representationMode;
    }

    public int requiredApprovals() {
        return requiredApprovals;
    }

    /** Always true. Support sessions log reads as well as writes. */
    public boolean auditsReads() {
        return auditsReads;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public String revokedByAccountId() {
        return revokedByAccountId;
    }

    public String sessionReportId() {
        return sessionReportId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /** Exactly what was ticked. Never widened, never inferred. */
    public Set<String> capabilities() {
        return Immutables.setCopyOf(capabilities);
    }

    /** The named representatives whose approval let this session exist. */
    public Set<TemporaryMasterApprover> approvers() {
        return Immutables.setCopyOf(approvers);
    }

    /**
     * Ends the session now.
     *
     * <p>Idempotent: revoking a revoked session keeps the first revocation,
     * because the first one is the one that ended the engineer's access and the
     * second would quietly rewrite who did it.
     */
    void revoke(String byAccountId, OffsetDateTime at) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokedByAccountId = byAccountId;
        }
    }

    void attachSessionReport(String reportId) {
        this.sessionReportId = reportId;
    }

    public boolean isActiveAt(OffsetDateTime now) {
        if (revokedAt != null && !now.isBefore(revokedAt)) {
            return false;
        }
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }

    /**
     * The real check: ticked <em>and</em> still within the window.
     *
     * <p>Deliberately requires the time, so that no caller can accidentally ask
     * the question without it. A capability on an expired session is not a
     * capability.
     */
    public boolean allowsAt(String capability, OffsetDateTime now) {
        return isActiveAt(now) && !Texts.isBlank(capability)
                && capabilities.contains(Texts.strip(capability));
    }
}
