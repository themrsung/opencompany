package com.coreintra.runtime.audit;

import com.coreintra.core.persistence.BusinessInstantEmbeddable;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * One audit row.
 *
 * <p>There are no setters and no update path. The class is shaped this way for
 * the same reason the table has a {@code BEFORE UPDATE OR DELETE} trigger: an
 * append-only log with a mutable mapping is append-only until someone calls a
 * setter. The trigger is the guarantee; this is the part of it a reviewer can
 * see without opening a migration.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogRow {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "actor_account_id", length = 36)
    private String actorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 24)
    private AuditActorKind actorKind;

    @Column(name = "actor_display_name", nullable = false, length = 200)
    private String actorDisplayName;

    @Column(name = "capability", nullable = false, length = 120)
    private String capability;

    @Column(name = "resource", nullable = false, length = 120)
    private String resource;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 24)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private AuditOutcome outcome;

    @Column(name = "rows_touched", nullable = false)
    private int rowsTouched;

    @Column(name = "before_json")
    private String beforeJson;

    @Column(name = "after_json")
    private String afterJson;

    /** Business time: the day the organisation agrees this happened on. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "businessDate", column = @Column(name = "occurred_business_date")),
            @AttributeOverride(name = "offsetSeconds", column = @Column(name = "occurred_offset_seconds")),
            @AttributeOverride(name = "absoluteTs", column = @Column(name = "occurred_absolute_ts",
                    insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable occurredAt;

    /** UTC: what the machine observed. Never conflated with the above. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "temporary_master_grant_id", length = 36)
    private String temporaryMasterGrantId;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected AuditLogRow() {
    }

    AuditLogRow(String id, AuditEvent event, OffsetDateTime createdAt) {
        this.id = id;
        this.companyId = event.companyId();
        this.actorAccountId = event.actorAccountId();
        this.actorKind = event.actorKind();
        this.actorDisplayName = event.actorDisplayName();
        this.capability = event.capability();
        this.resource = event.resource();
        this.resourceId = event.resourceId();
        this.action = event.action();
        this.outcome = event.outcome();
        this.rowsTouched = event.rowsTouched();
        this.beforeJson = event.beforeJson();
        this.afterJson = event.afterJson();
        this.occurredAt = BusinessInstantEmbeddable.from(event.occurredAt());
        this.createdAt = createdAt;
        this.requestId = event.requestId();
        this.ipAddress = event.ipAddress();
        this.userAgent = event.userAgent();
        this.temporaryMasterGrantId = event.temporaryMasterGrantId();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String actorAccountId() {
        return actorAccountId;
    }

    public AuditActorKind actorKind() {
        return actorKind;
    }

    public String actorDisplayName() {
        return actorDisplayName;
    }

    public String capability() {
        return capability;
    }

    public String resource() {
        return resource;
    }

    public String resourceId() {
        return resourceId;
    }

    public AuditAction action() {
        return action;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public int rowsTouched() {
        return rowsTouched;
    }

    public String beforeJson() {
        return beforeJson;
    }

    public String afterJson() {
        return afterJson;
    }

    public BusinessInstantEmbeddable occurredAt() {
        return occurredAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public String requestId() {
        return requestId;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public String userAgent() {
        return userAgent;
    }

    public String temporaryMasterGrantId() {
        return temporaryMasterGrantId;
    }
}
