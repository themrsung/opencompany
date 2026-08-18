package com.coreintra.core.permission;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The persistent form of a {@link PermissionGrant}.
 *
 * <p>Separate from the domain value so the evaluator never sees a JPA entity:
 * grants are read once per check and passed around as immutable values, and a
 * lazily-initialised entity leaking into that path would turn an authorisation
 * decision into a database access at an arbitrary later moment.
 */
@Entity
@Table(name = "permission_grant")
public class PermissionGrantRow {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private GrantSource source;

    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    @Column(name = "resource", nullable = false, length = 120)
    private String resource;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 24)
    private PermissionScope scope;

    @Column(name = "allow", nullable = false)
    private boolean allow;

    @Column(name = "granted_by", length = 36)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    /** Why this grant exists. Shown in the explainer beside the grant itself. */
    @Column(name = "reason")
    private String reason;

    protected PermissionGrantRow() {
    }

    public PermissionGrantRow(String id, GrantSource source, String sourceId, PermissionKey key,
            PermissionScope scope, boolean allow) {
        this.id = id;
        this.source = source;
        this.sourceId = sourceId;
        this.resource = key.resource();
        this.action = key.action();
        this.scope = scope;
        this.allow = allow;
        this.grantedAt = OffsetDateTime.now();
    }

    /** @param sourceLabel human-readable source name, resolved by the caller for the explainer */
    public PermissionGrant toDomain(String sourceLabel) {
        PermissionKey key = PermissionKey.of(resource, action);
        return allow
                ? PermissionGrant.allow(key, scope, source, sourceId, sourceLabel)
                : PermissionGrant.deny(key, scope, source, sourceId, sourceLabel);
    }

    public String id() {
        return id;
    }

    public GrantSource source() {
        return source;
    }

    public String sourceId() {
        return sourceId;
    }

    public PermissionKey key() {
        return PermissionKey.of(resource, action);
    }

    public PermissionScope scope() {
        return scope;
    }

    public boolean isAllow() {
        return allow;
    }

    public String grantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String value) {
        this.grantedBy = value;
    }

    public String reason() {
        return reason;
    }

    public void setReason(String value) {
        this.reason = value;
    }

    public OffsetDateTime grantedAt() {
        return grantedAt;
    }
}
