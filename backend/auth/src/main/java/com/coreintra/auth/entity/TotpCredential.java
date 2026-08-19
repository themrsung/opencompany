package com.coreintra.auth.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * An account's enrolled authenticator.
 *
 * <p>The secret is stored encrypted, not hashed — the server must compute codes
 * from it. It is the only reversibly-stored credential in the system.
 *
 * <p>{@code enrolledAt} being null means enrolment was started but never
 * confirmed. An unconfirmed credential cannot authenticate: otherwise a user
 * who scanned the QR and closed the tab would have a working second factor they
 * do not know about.
 */
@Entity
@Table(name = "totp_credential")
public class TotpCredential {

    @Id
    @Column(name = "account_id", length = 36)
    private String accountId;

    /** AES-256-GCM, {@code gcm1$nonce$ciphertext}. Never logged. */
    @Column(name = "secret_encrypted", nullable = false, length = 500)
    private String secretEncrypted;

    @Column(name = "digits", nullable = false)
    private int digits = 6;

    @Column(name = "step_seconds", nullable = false)
    private int stepSeconds = 30;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Null until the user proves possession by entering a code. */
    @Column(name = "enrolled_at")
    private OffsetDateTime enrolledAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    protected TotpCredential() {
    }

    public TotpCredential(String accountId, String secretEncrypted) {
        this.accountId = accountId;
        this.secretEncrypted = secretEncrypted;
        this.createdAt = OffsetDateTime.now();
    }

    public boolean isEnrolled() {
        return enrolledAt != null;
    }

    /** Called once the user has entered a correct code from the new secret. */
    public void confirmEnrolment() {
        this.enrolledAt = OffsetDateTime.now();
    }

    public void recordUse() {
        this.lastUsedAt = OffsetDateTime.now();
    }

    /** Replaces the secret, e.g. after a lost device. Re-enrolment is required. */
    public void replaceSecret(String newSecretEncrypted) {
        this.secretEncrypted = newSecretEncrypted;
        this.enrolledAt = null;
        this.createdAt = OffsetDateTime.now();
    }

    public String accountId() {
        return accountId;
    }

    public String secretEncrypted() {
        return secretEncrypted;
    }

    public int digits() {
        return digits;
    }

    public int stepSeconds() {
        return stepSeconds;
    }

    public OffsetDateTime enrolledAt() {
        return enrolledAt;
    }

    public OffsetDateTime lastUsedAt() {
        return lastUsedAt;
    }

    /** Never include the secret. A toString that leaks it will end up in a log. */
    @Override
    public String toString() {
        return "TotpCredential[account=" + accountId + " enrolled=" + isEnrolled() + "]";
    }
}
