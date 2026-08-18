package com.coreintra.runtime.webhook;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Subscriptions are retired, not deleted.
 *
 * <p>A delivery row references its subscription, and "we sent you this" has to
 * stay answerable after a client turns an integration off. Retirement also
 * keeps the url in the record: a support question about a leaked payload is
 * about where it went, which a deleted row cannot say.
 */
public interface WebhookSubscriptionRepository extends Repository<WebhookSubscriptionRow, String> {

    WebhookSubscriptionRow save(WebhookSubscriptionRow row);

    Optional<WebhookSubscriptionRow> findById(String id);

    List<WebhookSubscriptionRow> findByCompanyIdOrderByCreatedAtDesc(String companyId);

    /** The candidates for one event. Filtering by type happens on the loaded set. */
    List<WebhookSubscriptionRow> findByCompanyIdAndActiveTrueAndRetiredAtIsNull(String companyId);
}
