package com.coreintra.auth.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One sign-in attempt, successful or not. Drives progressive lockout.
 *
 * <p>Recorded against the <em>username as submitted</em>, not a resolved
 * account id, so that attempts against a non-existent username are rate-limited
 * too. Otherwise an attacker learns which usernames exist by noticing which
 * ones can be hammered without slowing down.
 */
@Entity
@Table(name = "auth_attempt")
public class AuthAttempt {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "succeeded", nullable = false)
    private boolean succeeded;

    /** Coarse reason for the audit log. Never returned to the client. */
    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    @Column(name = "attempted_at", nullable = false)
    private OffsetDateTime attemptedAt;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    protected AuthAttempt() {
    }

    public AuthAttempt(String id, String username, boolean succeeded, String failureReason) {
        this.id = id;
        this.username = username;
        this.succeeded = succeeded;
        this.failureReason = failureReason;
        this.attemptedAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String username() {
        return username;
    }

    public boolean succeeded() {
        return succeeded;
    }

    public String failureReason() {
        return failureReason;
    }

    public OffsetDateTime attemptedAt() {
        return attemptedAt;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public void setIpAddress(String value) {
        this.ipAddress = value;
    }
}
