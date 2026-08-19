package com.coreintra.runtime.webhook;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes events and drives their deliveries (§10).
 *
 * <p>A domain module calls {@link #record}, which writes an outbox row in that
 * module's own transaction and nothing else. The alternative - POSTing inside
 * the transaction that approved the document - makes a slow subscriber into a
 * slow approval, and a failed POST into a rolled-back approval. Nobody wants
 * their payroll run to depend on someone else's TLS certificate.
 *
 * <p>{@link #relayOutbox} then fans those rows out into deliveries, and
 * {@link #dispatchDue} sends them. Three steps rather than one, and each is
 * restartable, which is the property that makes an event impossible to lose
 * between a commit and a send.
 *
 * <h2>One box</h2>
 *
 * <p>The brief allows one deployment box, so {@link #dispatchDue} runs in this
 * process, driven by {@link WebhookDispatchScheduler} with a fixed delay so
 * that passes cannot overlap. It is not a distributed queue and does not
 * pretend to be; the {@code claimed_by} column exists so that becoming one
 * later is a migration rather than a redesign, and the scheduler can be
 * switched off per box in the meantime.
 */
@Service
public class WebhookService {

    /** Enough to make progress on a backlog; small enough that one pass is short. */
    private static final int BATCH = 100;

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final OutboxEventRepository outbox;
    private final WebhookSecretCipher cipher;
    private final WebhookTransport transport;
    private final Clock clock;
    private final Backoff backoff;
    private final String worker;

    public WebhookService(
            WebhookSubscriptionRepository subscriptions,
            WebhookDeliveryRepository deliveries,
            OutboxEventRepository outbox,
            WebhookSecretCipher cipher,
            WebhookTransport transport,
            Clock clock,
            Backoff backoff,
            @Value("${coreintra.webhooks.worker-name:app}") String worker) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.cipher = cipher;
        this.transport = transport;
        this.clock = clock;
        this.backoff = backoff;
        this.worker = worker;
    }

    /**
     * Registers a receiver. The secret is supplied by the caller and stored
     * encrypted; it is never returned by any read on this service.
     */
    @Transactional
    public WebhookSubscriptionRow subscribe(
            String companyId,
            String url,
            String plaintextSecret,
            String description,
            Set<String> eventTypes,
            String byAccountId) {
        if (Texts.isBlank(plaintextSecret)) {
            throw new IllegalArgumentException(
                    "a subscription without a secret cannot be signed, and an unsigned "
                            + "webhook is one a receiver has no reason to believe");
        }
        return subscriptions.save(new WebhookSubscriptionRow(
                UUID.randomUUID().toString(),
                companyId,
                url,
                cipher.encrypt(plaintextSecret),
                visiblePrefixOf(plaintextSecret),
                description,
                eventTypes,
                byAccountId,
                OffsetDateTime.now(clock)));
    }

    /**
     * The first few characters, for telling two secrets apart in a UI.
     *
     * <p>Eight characters of a secret with real entropy narrows nothing that
     * matters; a receiver's secret is at least 32. It is a label, and it is
     * deliberately not enough to be a hint.
     */
    static String visiblePrefixOf(String plaintextSecret) {
        String trimmed = Texts.strip(plaintextSecret);
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8);
    }

    /**
     * Records an event in the caller's own transaction (the outbox).
     *
     * <p>This is what a domain module calls, not {@link #publish}. The row is
     * committed with the approval or the document it describes, so the event
     * cannot be lost in the gap between the commit and the send. Fan-out to
     * subscriptions happens later, in {@link #relayOutbox}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventRow record(String companyId, String eventType, String resource,
                                 String resourceId, String payload, BusinessInstant occurredAt) {
        String eventId = UUID.randomUUID().toString();
        return outbox.save(new OutboxEventRow(
                eventId,
                companyId,
                new WebhookEvent(eventId, eventType, payload),
                resource,
                resourceId,
                BusinessInstantEmbeddable.from(occurredAt),
                OffsetDateTime.now(clock)));
    }

    /**
     * Turns outbox rows into deliveries.
     *
     * <p>Not one transaction around the loop, and deliberately not one per row
     * either. Every step here is a single-row write that Spring Data commits on
     * its own, and every step is idempotent: a fan-out that dies half way leaves
     * the outbox row unrelayed, and the next pass re-publishes, skipping the
     * deliveries that already exist because a delivery is unique per
     * (subscription, event).
     *
     * <p>That restartability is what makes the pass safe, and it is worth being
     * explicit about, because annotating {@link #relayOne} would not have given
     * a transaction per row anyway - a call from here to there is a plain method
     * call that never passes the proxy.
     *
     * @return how many events were fanned out
     */
    public int relayOutbox() {
        List<OutboxEventRow> pending =
                outbox.findByRelayedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, BATCH));
        int relayed = 0;
        for (OutboxEventRow event : pending) {
            if (relayOne(event.id())) {
                relayed++;
            }
        }
        return relayed;
    }

    /** One event's fan-out. Safe to call again after any failure. */
    public boolean relayOne(String outboxEventId) {
        OutboxEventRow event = outbox.findById(outboxEventId).orElse(null);
        if (event == null || event.isRelayed()) {
            return false;
        }
        try {
            publish(event.companyId(), event.asEvent());
            event.relayed(OffsetDateTime.now(clock));
            outbox.save(event);
            return true;
        } catch (RuntimeException failed) {
            // Left unrelayed on purpose: the next pass tries again. Swallowing
            // this would lose the event; rethrowing would stop the whole pass
            // for one bad row.
            event.relayFailed(failed.getClass().getSimpleName() + ": " + failed.getMessage());
            outbox.save(event);
            return false;
        }
    }

    @Transactional
    public void retire(String subscriptionId) {
        WebhookSubscriptionRow row = subscriptions.findById(subscriptionId).orElseThrow(
                () -> new IllegalArgumentException("no such subscription: " + subscriptionId));
        row.retire(OffsetDateTime.now(clock));
        subscriptions.save(row);
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscriptionRow> subscriptionsFor(String companyId) {
        return subscriptions.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    /**
     * Records one event for every subscriber that wants it.
     *
     * <p>The signature stored here is the one the first attempt will carry.
     * Later attempts re-sign, because {@link WebhookSigner} puts the timestamp
     * inside the signature and a receiver is meant to refuse a stale one: a
     * retry two hours later carrying the original timestamp would be rejected
     * as a replay, which is exactly the protection working against us. The
     * stored value is kept because it is what we said we sent, and a client
     * comparing their logs to ours needs it to still be there.
     *
     * @return the deliveries created; empty when nobody subscribed, which is
     *         not an error - the event still happened.
     */
    @Transactional
    public List<WebhookDeliveryRow> publish(String companyId, WebhookEvent event) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<WebhookDeliveryRow> created = new ArrayList<WebhookDeliveryRow>();
        for (WebhookSubscriptionRow subscription
                : subscriptions.findByCompanyIdAndActiveTrueAndRetiredAtIsNull(companyId)) {
            if (!subscription.wants(event.type())) {
                continue;
            }
            if (deliveries.findBySubscriptionIdAndEventId(subscription.id(), event.id()).isPresent()) {
                // The same event published twice is one delivery. The unique
                // index says so too; this check keeps the friendlier error.
                continue;
            }
            String signature = WebhookSigner.sign(
                    cipher.decrypt(subscription.secretEncrypted()), event.payload(), now);
            created.add(deliveries.save(new WebhookDeliveryRow(
                    UUID.randomUUID().toString(),
                    subscription.id(),
                    companyId,
                    event,
                    signature,
                    backoff.maxAttempts(),
                    now)));
        }
        return created;
    }

    /**
     * One dispatch pass. Returns how many deliveries were attempted.
     *
     * <p>No transaction spans the loop. A subscriber that times out must not
     * roll back the attempt counters of the subscribers already answered in this
     * pass, or one bad endpoint would keep the whole queue at attempt zero
     * forever. Each attempt ends in a single-row update, which is atomic on its
     * own.
     */
    public int dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<WebhookDeliveryRow> due = deliveries.findByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                WebhookDeliveryRow.PENDING, now, PageRequest.of(0, BATCH));
        int attempted = 0;
        for (WebhookDeliveryRow delivery : due) {
            if (attemptOne(delivery.id())) {
                attempted++;
            }
        }
        return attempted;
    }

    /**
     * Attempts one delivery.
     *
     * @return false when the row is no longer pending, or its subscription has
     *         been retired underneath it
     */
    public boolean attemptOne(String deliveryId) {
        WebhookDeliveryRow delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || !delivery.isDueAt(OffsetDateTime.now(clock))) {
            return false;
        }
        WebhookSubscriptionRow subscription =
                subscriptions.findById(delivery.subscriptionId()).orElse(null);
        if (subscription == null || !subscription.isActive()) {
            // Retired mid-flight. The receiver did not fail, so this is not a
            // dead delivery; it is one we stopped. Marking it terminal also
            // takes it out of the due query, which is the other half of the
            // reason a fourth state exists.
            delivery.cancel("subscription retired before this delivery was made",
                    OffsetDateTime.now(clock));
            deliveries.save(delivery);
            return false;
        }
        OffsetDateTime at = OffsetDateTime.now(clock);
        delivery.claim(worker, at);
        String header = WebhookSigner.sign(
                cipher.decrypt(subscription.secretEncrypted()), delivery.payload(), at);
        WebhookTransport.Result result = transport.post(subscription.url(), delivery.payload(), header);
        if (result.isDelivered()) {
            delivery.succeeded(result.statusCode().intValue(), OffsetDateTime.now(clock));
        } else {
            delivery.failed(result.statusCode(), result.error(), backoff, OffsetDateTime.now(clock));
        }
        deliveries.save(delivery);
        return true;
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryRow> recentDeliveries(String subscriptionId, int limit) {
        return deliveries.findBySubscriptionIdOrderByCreatedAtDesc(
                subscriptionId, PageRequest.of(0, limit));
    }
}
