package com.coreintra.auth.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A scoped key for a service account: a client module, an MCP client, a job.
 *
 * <h2>The prefix</h2>
 *
 * <p>Keys are {@code ci_<prefix>_<secret>}. The prefix is stored in clear and
 * the secret is hashed. That gives three things at once: an operator can
 * identify a key in a log or a config file without holding the secret, a leaked
 * key can be recognised by its prefix, and lookup is an indexed hit on the
 * prefix rather than a hash comparison against every row.
 *
 * <h2>Scopes narrow, never widen</h2>
 *
 * <p>A key's scopes are intersected with its account's permissions. A key
 * scoped to reads cannot write even if the account may, and no scope grants
 * anything the account lacks. This is what makes handing an MCP client a
 * read-only key meaningful.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Clear, indexed, identifies the key without revealing it. */
    @Column(name = "key_prefix", nullable = false, length = 24)
    private String keyPrefix;

    @Column(name = "secret_hash", nullable = false, length = 200)
    private String secretHash;

    /**
     * Comma-separated scopes. A write scope must be ticked explicitly — an MCP
     * token does not get write tools by default.
     */
    @Column(name = "scopes", nullable = false, length = 2000)
    private String scopes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    /** Null means no expiry, which the UI warns about rather than hiding. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected ApiKey() {
    }

    public ApiKey(String id, String accountId, String name, String keyPrefix, String secretHash,
            String scopes) {
        this.id = id;
        this.accountId = accountId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.secretHash = secretHash;
        this.scopes = scopes;
        this.createdAt = OffsetDateTime.now();
    }

    public boolean isUsable(OffsetDateTime now) {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }

    public void recordUse() {
        this.lastUsedAt = OffsetDateTime.now();
    }

    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = OffsetDateTime.now();
        }
    }

    public java.util.Set<String> scopeSet() {
        java.util.Set<String> parsed = new java.util.LinkedHashSet<String>();
        for (String scope : scopes.split(",")) {
            String trimmed = scope.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed;
    }

    public String id() {
        return id;
    }

    public String accountId() {
        return accountId;
    }

    public String name() {
        return name;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public String secretHash() {
        return secretHash;
    }

    public String scopes() {
        return scopes;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        this.createdBy = value;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime value) {
        this.expiresAt = value;
    }

    public OffsetDateTime lastUsedAt() {
        return lastUsedAt;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }
}
