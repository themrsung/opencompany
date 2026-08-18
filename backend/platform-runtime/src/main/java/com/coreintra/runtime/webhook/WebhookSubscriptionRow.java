package com.coreintra.runtime.webhook;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where one client wants to be told things, and what it wants to be told (§10).
 *
 * <p>Subscribed event types are rows rather than a column: a client that wants
 * approvals but not attendance says so by what it does not tick, and a set of
 * rows can be indexed, added to and audited one at a time. A comma-separated
 * column would have to be parsed to answer "who wants this event?".
 *
 * <h2>The secret is encrypted, not hashed</h2>
 *
 * <p>Signing needs the secret back, so it cannot be hashed the way a password
 * is. It is encrypted under the installation secret so that a database dump
 * alone does not let an attacker forge our signature - the same reasoning as
 * {@code totp_credential} in {@code V3__auth.sql}. This class holds the stored
 * ciphertext; decryption belongs to whoever holds the installation key.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscriptionRow {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "url", nullable = false, length = 2000)
    private String url;

    @Column(name = "secret_encrypted", nullable = false, length = 500)
    private String secretEncrypted;

    /**
     * The visible half of the secret.
     *
     * <p>Enough to tell two secrets apart in the UI and on a support call,
     * useless for signing. Without it the only way to answer "which secret is
     * this subscription using?" is to decrypt one, which is the operation this
     * design is trying to make rare.
     */
    @Column(name = "secret_prefix", nullable = false, length = 12)
    private String secretPrefix;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by_account_id", length = 36)
    private String createdByAccountId;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    /** One row per subscribed event type. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "webhook_subscription_event",
            joinColumns = @JoinColumn(name = "subscription_id"))
    @Column(name = "event_type", nullable = false, length = 60)
    private Set<String> eventTypes = new LinkedHashSet<String>();

    /** JPA requires a no-arg constructor. Not for application use. */
    protected WebhookSubscriptionRow() {
    }

    public WebhookSubscriptionRow(String id, String companyId, String url, String secretEncrypted,
                                  String secretPrefix, String description, Set<String> eventTypes,
                                  String createdByAccountId, OffsetDateTime createdAt) {
        if (Texts.isBlank(url) || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IllegalArgumentException(
                    "a webhook url must be absolute http(s); got \"" + url + "\"");
        }
        if (Texts.isBlank(secretEncrypted)) {
            throw new IllegalArgumentException(
                    "a subscription without a secret cannot be signed, and an unsigned "
                            + "webhook is one a receiver has no reason to believe");
        }
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "a subscription to nothing is not a subscription; name at least one event type");
        }
        this.id = id;
        this.companyId = companyId;
        this.url = url;
        this.secretEncrypted = secretEncrypted;
        this.secretPrefix = secretPrefix;
        this.description = description;
        this.eventTypes = new LinkedHashSet<String>(eventTypes);
        this.createdByAccountId = createdByAccountId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String url() {
        return url;
    }

    public String secretEncrypted() {
        return secretEncrypted;
    }

    /** Safe to show. Never the secret itself. */
    public String secretPrefix() {
        return secretPrefix;
    }

    public String description() {
        return description;
    }

    public boolean isActive() {
        return active && retiredAt == null;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public String createdByAccountId() {
        return createdByAccountId;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public Set<String> eventTypes() {
        return Immutables.setCopyOf(eventTypes);
    }

    public boolean wants(String eventType) {
        return isActive() && eventTypes.contains(eventType);
    }

    /** Stops delivery without losing the history of what was delivered. */
    public void retire(OffsetDateTime at) {
        if (retiredAt == null) {
            this.retiredAt = at;
            this.active = false;
            this.updatedAt = at;
        }
    }
}
