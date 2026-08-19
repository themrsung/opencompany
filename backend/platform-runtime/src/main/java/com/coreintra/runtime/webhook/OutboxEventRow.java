package com.coreintra.runtime.webhook;

import com.coreintra.core.persistence.BusinessInstantEmbeddable;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * An event, recorded in the same transaction as the change it describes.
 *
 * <h2>Why an outbox rather than publishing inline</h2>
 *
 * <p>A document is approved, and the client's system should hear about it.
 * Doing that inline goes wrong in one of two ways, and no amount of care picks
 * a third.
 *
 * <p>POST inside the approval's transaction, and a subscriber whose certificate
 * expired last night makes 결재 slow for everyone; a subscriber returning 500
 * rolls back an approval that genuinely happened. Nobody wants their payroll run
 * to depend on someone else's TLS.
 *
 * <p>POST after the commit instead, and a restart in the gap loses the event
 * with nothing anywhere recording it was owed. The failure is silent, and the
 * client finds out weeks later that one approval never arrived.
 *
 * <p>Writing this row in the domain's own transaction removes the gap: the
 * event exists exactly when the thing it describes exists, both or neither.
 * Everything after this point is at-least-once and can be retried freely,
 * because a delivery is unique per (subscription, event).
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventRow {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "resource", nullable = false, length = 120)
    private String resource;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Column(name = "payload", nullable = false)
    private String payload;

    /** The business day the event belongs to, not the day the relay ran. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "businessDate", column = @Column(name = "occurred_business_date")),
            @AttributeOverride(name = "offsetSeconds", column = @Column(name = "occurred_offset_seconds")),
            @AttributeOverride(name = "absoluteTs", column = @Column(name = "occurred_absolute_ts",
                    insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable occurredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "relayed_at")
    private OffsetDateTime relayedAt;

    @Column(name = "relay_error", length = 400)
    private String relayError;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected OutboxEventRow() {
    }

    public OutboxEventRow(String id, String companyId, WebhookEvent event, String resource,
                          String resourceId, BusinessInstantEmbeddable occurredAt,
                          OffsetDateTime createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.eventType = event.type();
        this.resource = resource;
        this.resourceId = resourceId;
        this.payload = event.payload();
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String eventType() {
        return eventType;
    }

    public String resource() {
        return resource;
    }

    public String resourceId() {
        return resourceId;
    }

    public String payload() {
        return payload;
    }

    public BusinessInstantEmbeddable occurredAt() {
        return occurredAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime relayedAt() {
        return relayedAt;
    }

    public String relayError() {
        return relayError;
    }

    public boolean isRelayed() {
        return relayedAt != null;
    }

    /** The event, as the delivery layer wants it. The row id is the event id. */
    public WebhookEvent asEvent() {
        return new WebhookEvent(id, eventType, payload);
    }

    /**
     * Marks the fan-out done.
     *
     * <p>The row stays afterwards. "Was this event ever emitted?" is a question
     * clients ask months later, and an outbox that deletes what it relayed
     * cannot answer it.
     */
    void relayed(OffsetDateTime at) {
        this.relayedAt = at;
        this.relayError = null;
    }

    /** Left unrelayed so the next pass tries again, with the reason recorded. */
    void relayFailed(String error) {
        this.relayError = error == null || error.length() <= 400
                ? error
                : error.substring(0, 400);
    }
}
