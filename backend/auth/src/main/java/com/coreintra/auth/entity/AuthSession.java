package com.coreintra.auth.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A signed-in session, revocable server-side.
 *
 * <p>Tokens are stored hashed and never in plaintext, so a database dump does
 * not hand over live sessions.
 *
 * <h2>Refresh rotation and reuse detection</h2>
 *
 * <p>Every refresh issues a new refresh token and retires the old one. If a
 * retired token is ever presented again, that means it was captured — the
 * legitimate client has already moved on — so the whole session chain is
 * revoked rather than just refusing that one request. {@link #revokedReason}
 * records which of those two things happened.
 */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    /** Hashed. Rotated on every refresh. */
    @Column(name = "refresh_token_hash", nullable = false, length = 200)
    private String refreshTokenHash;

    /** Chain identifier, stable across rotations, so reuse revokes the lineage. */
    @Column(name = "chain_id", nullable = false, length = 36)
    private String chainId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;

    /** Shown in the active-session list so a user recognises their own devices. */
    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    protected AuthSession() {
    }

    public AuthSession(String id, String accountId, String chainId, String refreshTokenHash,
            OffsetDateTime expiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.chainId = chainId;
        this.refreshTokenHash = refreshTokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = OffsetDateTime.now();
    }

    public boolean isActive(OffsetDateTime now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void rotate(String newRefreshTokenHash, OffsetDateTime newExpiry) {
        this.refreshTokenHash = newRefreshTokenHash;
        this.expiresAt = newExpiry;
        this.lastUsedAt = OffsetDateTime.now();
    }

    public void revoke(String reason) {
        if (revokedAt == null) {
            this.revokedAt = OffsetDateTime.now();
            this.revokedReason = reason;
        }
    }

    public String id() {
        return id;
    }

    public String accountId() {
        return accountId;
    }

    public String chainId() {
        return chainId;
    }

    public String refreshTokenHash() {
        return refreshTokenHash;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public OffsetDateTime lastUsedAt() {
        return lastUsedAt;
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public String revokedReason() {
        return revokedReason;
    }

    public String userAgent() {
        return userAgent;
    }

    public void setUserAgent(String value) {
        this.userAgent = value;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public void setIpAddress(String value) {
        this.ipAddress = value;
    }
}
