package com.coreintra.runtime.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Deliveries are a log of attempts, so there is no delete here either.
 *
 * <p>A dead delivery is the row a client asks about weeks later. Sweeping them
 * would answer "we have no record", which is the one answer that is never true.
 */
public interface WebhookDeliveryRepository extends Repository<WebhookDeliveryRow, String> {

    WebhookDeliveryRow save(WebhookDeliveryRow row);

    Optional<WebhookDeliveryRow> findById(String id);

    /**
     * The dispatcher's work queue: pending, scheduled, oldest first.
     *
     * <p>Paged because a subscriber that has been down for a day can leave
     * thousands due at once, and one pass should make progress on all of them
     * rather than load them all.
     */
    List<WebhookDeliveryRow> findByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String state, OffsetDateTime now, Pageable pageable);

    List<WebhookDeliveryRow> findBySubscriptionIdOrderByCreatedAtDesc(
            String subscriptionId, Pageable pageable);

    /** One row per (subscription, event); this is how the unique index is honoured in code. */
    Optional<WebhookDeliveryRow> findBySubscriptionIdAndEventId(String subscriptionId, String eventId);

    long countByStateAndCompanyId(String state, String companyId);
}
