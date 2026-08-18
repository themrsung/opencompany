package com.coreintra.runtime.webhook;

import com.coreintra.compat.Texts;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * One event, on its way to one subscriber.
 *
 * <p>One row per (subscription, event), not per attempt: a redelivery is the
 * same row moving on, so a subscriber cannot be sent an event twice by a
 * dispatcher that restarted mid-flight. Attempts are counted here rather than
 * kept as children because the only question ever asked of them is "how many,
 * and when next".
 *
 * <h2>Four states, and the transitions are one-way</h2>
 *
 * <p>{@code PENDING} always carries a schedule; the three terminal states never
 * do. {@code DEAD} additionally requires the attempts to have been made - giving
 * up is only honest once they were - and the same rule is a CHECK constraint,
 * because a row inserted by a repair script is a row this class never saw.
 *
 * <p>{@code CANCELLED} is not {@code DEAD}. Dead is what the receiver did to us;
 * cancelled is what we did to the receiver, and a client reading their delivery
 * history should not be told their endpoint failed when they turned it off.
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDeliveryRow {

    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String DEAD = "DEAD";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "subscription_id", nullable = false, length = 36)
    private String subscriptionId;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    /** The business fact. Two subscribers to one event share this id. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "signature", nullable = false, length = 200)
    private String signature;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error", length = 400)
    private String lastError;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "dead_at")
    private OffsetDateTime deadAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected WebhookDeliveryRow() {
    }

    public WebhookDeliveryRow(String id, String subscriptionId, String companyId, WebhookEvent event,
                              String signature, int maxAttempts, OffsetDateTime createdAt) {
        if (maxAttempts < 1 || maxAttempts > Backoff.MAX_ATTEMPTS_CEILING) {
            throw new IllegalArgumentException(
                    "attempts must be between 1 and " + Backoff.MAX_ATTEMPTS_CEILING);
        }
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.companyId = companyId;
        this.eventId = event.id();
        this.eventType = event.type();
        this.payload = event.payload();
        this.signature = signature;
        this.state = PENDING;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String subscriptionId() {
        return subscriptionId;
    }

    public String companyId() {
        return companyId;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    /** The value the receiver compares against; see {@link WebhookSigner}. */
    public String signature() {
        return signature;
    }

    public String state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public OffsetDateTime nextAttemptAt() {
        return nextAttemptAt;
    }

    public Integer lastStatusCode() {
        return lastStatusCode;
    }

    public String lastError() {
        return lastError;
    }

    public OffsetDateTime claimedAt() {
        return claimedAt;
    }

    public String claimedBy() {
        return claimedBy;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime deliveredAt() {
        return deliveredAt;
    }

    public OffsetDateTime deadAt() {
        return deadAt;
    }

    public OffsetDateTime cancelledAt() {
        return cancelledAt;
    }

    public boolean isPending() {
        return PENDING.equals(state);
    }

    public boolean isDelivered() {
        return DELIVERED.equals(state);
    }

    public boolean isDead() {
        return DEAD.equals(state);
    }

    public boolean isCancelled() {
        return CANCELLED.equals(state);
    }

    /** True when a dispatcher may pick this up now. */
    public boolean isDueAt(OffsetDateTime now) {
        return isPending() && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    void claim(String worker, OffsetDateTime at) {
        this.claimedBy = worker;
        this.claimedAt = at;
    }

    /** The receiver answered 2xx. Terminal, and the row keeps the count it took. */
    void succeeded(int statusCode, OffsetDateTime at) {
        this.attempts = this.attempts + 1;
        this.state = DELIVERED;
        this.lastStatusCode = statusCode;
        this.lastError = null;
        this.deliveredAt = at;
        this.nextAttemptAt = null;
    }

    /**
     * The attempt failed. Schedules the next one, or gives up.
     *
     * @return true when this failure exhausted the budget and the row is now dead
     */
    boolean failed(Integer statusCode, String error, Backoff backoff, OffsetDateTime at) {
        this.attempts = this.attempts + 1;
        this.lastStatusCode = statusCode;
        this.lastError = trim(error);
        if (this.attempts >= this.maxAttempts) {
            this.state = DEAD;
            this.deadAt = at;
            this.nextAttemptAt = null;
            return true;
        }
        this.nextAttemptAt = at.plus(backoff.delayAfter(this.attempts));
        return false;
    }

    /**
     * We stopped sending; the receiver never had a chance to fail.
     *
     * <p>The reason is required rather than optional: this is the one terminal
     * state a client did not cause by being unreachable, so the row has to say
     * so itself.
     */
    void cancel(String reason, OffsetDateTime at) {
        if (Texts.isBlank(reason)) {
            throw new IllegalArgumentException(
                    "a cancelled delivery must say why; the receiver did not fail");
        }
        this.state = CANCELLED;
        this.cancelledAt = at;
        this.lastError = trim(reason);
        this.nextAttemptAt = null;
    }

    private static String trim(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 400 ? error : error.substring(0, 400);
    }
}
