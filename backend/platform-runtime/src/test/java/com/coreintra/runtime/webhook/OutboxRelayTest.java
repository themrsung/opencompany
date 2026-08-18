package com.coreintra.runtime.webhook;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transactional outbox: an event committed with its domain change, and
 * relayed afterwards.
 */
class OutboxRelayTest {

    private static final String PAYLOAD = "{\"documentId\":\"d-1\",\"amount\":\"1400000.25\"}";
    private static final BusinessInstant APPROVED_AT =
            BusinessInstant.of(LocalDate.of(2026, 8, 18), 27, 0, 0);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T09:00:00Z"));
    private final FakeSubscriptions subscriptions = new FakeSubscriptions();
    private final FakeDeliveries deliveries = new FakeDeliveries();
    private final FakeOutbox outbox = new FakeOutbox();
    private final CountingTransport transport = new CountingTransport();

    private final WebhookService service = new WebhookService(
            subscriptions, deliveries, outbox, new PassThroughCipher(), transport, clock,
            new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 4), "test");

    @Test
    @DisplayName("an event with no subscribers is still recorded, and still relays")
    void recordedEvenWithNobodyListening() {
        OutboxEventRow recorded = record();

        assertThat(recorded.isRelayed()).isFalse();
        assertThat(service.relayOutbox()).isEqualTo(1);
        assertThat(outbox.byId.get(recorded.id()).isRelayed())
                .as("nobody wanted it, which is not a failure to relay it")
                .isTrue();
        assertThat(deliveries.byId).isEmpty();
    }

    @Test
    @DisplayName("the event keeps the business day it happened on, not the day it was relayed")
    void keepsBusinessTime() {
        OutboxEventRow recorded = record();
        clock.advance(Duration.ofDays(2));
        service.relayOutbox();

        assertThat(recorded.occurredAt().toBusinessInstant())
                .as("a 03:00 approval belongs to the previous business day, whenever it is sent")
                .isEqualTo(APPROVED_AT);
    }

    @Test
    @DisplayName("relaying turns one event into one delivery per interested subscriber")
    void fansOutToSubscribers() {
        subscribe("approval.approved");
        subscribe("approval.approved");
        subscribe("document.created");
        record();

        assertThat(service.relayOutbox()).isEqualTo(1);
        assertThat(deliveries.byId).hasSize(2);
    }

    @Test
    @DisplayName("a relayed event is not relayed twice")
    void relayIsOnce() {
        subscribe("approval.approved");
        record();

        assertThat(service.relayOutbox()).isEqualTo(1);
        assertThat(service.relayOutbox()).isZero();
        assertThat(deliveries.byId).hasSize(1);
    }

    @Test
    @DisplayName("a relay that fails leaves the event pending, with the reason, and retries")
    void failedRelayIsRetried() {
        subscribe("approval.approved");
        OutboxEventRow recorded = record();
        subscriptions.failNextRead = true;

        assertThat(service.relayOutbox()).isZero();
        assertThat(outbox.byId.get(recorded.id()).isRelayed())
                .as("an event that could not be fanned out is still owed")
                .isFalse();
        assertThat(outbox.byId.get(recorded.id()).relayError()).contains("subscriptions unavailable");

        assertThat(service.relayOutbox()).isEqualTo(1);
        assertThat(deliveries.byId).hasSize(1);
    }

    private OutboxEventRow record() {
        return service.record("c1", "approval.approved", "approvalDocument", "d-1", PAYLOAD,
                APPROVED_AT);
    }

    private void subscribe(String eventType) {
        service.subscribe("c1", "https://client.example/hooks", "whsec-abcdef0123456789",
                "client system", Immutables.setOf(eventType), "master-1");
    }

    private static final class PassThroughCipher implements WebhookSecretCipher {

        @Override
        public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override
        public String decrypt(String stored) {
            return stored;
        }
    }

    private static final class CountingTransport implements WebhookTransport {

        @Override
        public Result post(String url, String payload, String signatureHeader) {
            return Result.of(200);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            this.now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeOutbox implements OutboxEventRepository {

        private final Map<String, OutboxEventRow> byId = new LinkedHashMap<String, OutboxEventRow>();

        @Override
        public OutboxEventRow save(OutboxEventRow row) {
            byId.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<OutboxEventRow> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<OutboxEventRow> findByRelayedAtIsNullOrderByCreatedAtAsc(Pageable pageable) {
            List<OutboxEventRow> pending = new ArrayList<OutboxEventRow>();
            for (OutboxEventRow row : byId.values()) {
                if (!row.isRelayed()) {
                    pending.add(row);
                }
            }
            return pending;
        }

        @Override
        public List<OutboxEventRow> findByCompanyIdAndResourceAndResourceIdOrderByCreatedAtDesc(
                String companyId, String resource, String resourceId) {
            return new ArrayList<OutboxEventRow>(byId.values());
        }
    }

    private static final class FakeSubscriptions implements WebhookSubscriptionRepository {

        private final Map<String, WebhookSubscriptionRow> byId =
                new LinkedHashMap<String, WebhookSubscriptionRow>();

        /** Stands in for the database being briefly unavailable. */
        private boolean failNextRead;

        @Override
        public WebhookSubscriptionRow save(WebhookSubscriptionRow row) {
            byId.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<WebhookSubscriptionRow> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<WebhookSubscriptionRow> findByCompanyIdOrderByCreatedAtDesc(String companyId) {
            return new ArrayList<WebhookSubscriptionRow>(byId.values());
        }

        @Override
        public List<WebhookSubscriptionRow> findByCompanyIdAndActiveTrueAndRetiredAtIsNull(
                String companyId) {
            if (failNextRead) {
                failNextRead = false;
                throw new IllegalStateException("subscriptions unavailable");
            }
            List<WebhookSubscriptionRow> live = new ArrayList<WebhookSubscriptionRow>();
            for (WebhookSubscriptionRow row : byId.values()) {
                if (row.companyId().equals(companyId) && row.isActive()) {
                    live.add(row);
                }
            }
            return live;
        }
    }

    private static final class FakeDeliveries implements WebhookDeliveryRepository {

        private final Map<String, WebhookDeliveryRow> byId =
                new LinkedHashMap<String, WebhookDeliveryRow>();

        @Override
        public WebhookDeliveryRow save(WebhookDeliveryRow row) {
            byId.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<WebhookDeliveryRow> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<WebhookDeliveryRow> findByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                String state, OffsetDateTime now, Pageable pageable) {
            List<WebhookDeliveryRow> due = new ArrayList<WebhookDeliveryRow>();
            for (WebhookDeliveryRow row : byId.values()) {
                if (row.state().equals(state) && row.nextAttemptAt() != null
                        && !row.nextAttemptAt().isAfter(now)) {
                    due.add(row);
                }
            }
            return due;
        }

        @Override
        public List<WebhookDeliveryRow> findBySubscriptionIdOrderByCreatedAtDesc(
                String subscriptionId, Pageable pageable) {
            return new ArrayList<WebhookDeliveryRow>(byId.values());
        }

        @Override
        public Optional<WebhookDeliveryRow> findBySubscriptionIdAndEventId(
                String subscriptionId, String eventId) {
            for (WebhookDeliveryRow row : byId.values()) {
                if (row.subscriptionId().equals(subscriptionId) && row.eventId().equals(eventId)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override
        public long countByStateAndCompanyId(String state, String companyId) {
            return byId.size();
        }
    }
}
