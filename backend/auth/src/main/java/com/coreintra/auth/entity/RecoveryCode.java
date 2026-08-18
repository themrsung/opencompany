package com.coreintra.auth.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A single-use code for signing in when the authenticator device is gone.
 *
 * <p>These are the only bearer secrets in the system. Consequently:
 * shown exactly once at generation, stored hashed, and each usable once. The
 * remaining count is surfaced in the UI, because a user who has burned nine of
 * ten needs to know before the tenth.
 */
@Entity
@Table(name = "recovery_code")
public class RecoveryCode {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    /** Salted SHA-256. The plaintext exists only in the response that created it. */
    @Column(name = "code_hash", nullable = false, length = 200)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    protected RecoveryCode() {
    }

    public RecoveryCode(String id, String accountId, String codeHash) {
        this.id = id;
        this.accountId = accountId;
        this.codeHash = codeHash;
        this.createdAt = OffsetDateTime.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    /**
     * Marks this code spent.
     *
     * <p>Refuses a second use rather than being idempotent: reaching here twice
     * means a code was accepted twice, which is the failure this class exists
     * to prevent, and it should be loud.
     */
    public void markUsed() {
        if (usedAt != null) {
            throw new IllegalStateException(
                    "recovery code " + id + " was already used at " + usedAt);
        }
        this.usedAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String accountId() {
        return accountId;
    }

    public String codeHash() {
        return codeHash;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime usedAt() {
        return usedAt;
    }
}
