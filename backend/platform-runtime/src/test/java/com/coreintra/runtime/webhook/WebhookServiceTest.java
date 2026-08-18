package com.coreintra.runtime.webhook;

import com.coreintra.compat.Immutables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookServiceTest {

    private static final String PAYLOAD =
            "{\"type\":\"approval.approved\",\"documentId\":\"d-1\"}";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T09:00:00Z"));
    private final FakeSubscriptions subscriptions = new FakeSubscriptions();
    private final FakeDeliveries deliveries = new FakeDeliveries();
    private final FakeOutbox outbox = new FakeOutbox();
    private final RecordingTransport transport = new RecordingTransport();
    private final Backoff backoff = new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 4);

    private final WebhookService service = new WebhookService(
            subscriptions, deliveries, outbox, new PassThroughCipher(), transport, clock, backoff,
            "test");

    @Test
    @DisplayName("an event is queued only for the subscribers that ticked its type")
    void publishesToInterestedSubscribersOnly() {
        WebhookSubscriptionRow interested = subscribe("approval.approved");
        subscribe("document.created");

        List<WebhookDeliveryRow> created = service.publish("c1", event("e-1"));

        assertThat(created).hasSize(1);
        assertThat(created.get(0).subscriptionId()).isEqualTo(interested.id());
        assertThat(created.get(0).state()).isEqualTo(WebhookDeliveryRow.PENDING);
    }

    @Test
    @DisplayName("publishing the same event twice does not queue it twice")
    void publishIsIdempotentPerEvent() {
        subscribe("approval.approved");

        service.publish("c1", event("e-1"));
        List<WebhookDeliveryRow> again = service.publish("c1", event("e-1"));

        assertThat(again).isEmpty();
        assertThat(deliveries.byId).hasSize(1);
    }

    @Test
    @DisplayName("a delivery the receiver accepts is done, and is not sent again")
    void successIsTerminal() {
        subscribe("approval.approved");
        service.publish("c1", event("e-1"));
        transport.answerWith(WebhookTransport.Result.of(200));

        assertThat(service.dispatchDue()).isEqualTo(1);

        WebhookDeliveryRow delivered = only();
        assertThat(delivered.isDelivered()).isTrue();
        assertThat(delivered.attempts()).isEqualTo(1);
        assertThat(delivered.nextAttemptAt()).isNull();

        clock.advance(Duration.ofHours(1));
        assertThat(service.dispatchDue()).isZero();
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("a signed delivery arrives with a signature the receiver can verify")
    void deliveryCarriesAVerifiableSignature() {
        subscribe("approval.approved");
        service.publish("c1", event("e-1"));
        transport.answerWith(WebhookTransport.Result.of(200));

        service.dispatchDue();

        assertThat(WebhookSigner.verify("whsec-1", PAYLOAD, transport.lastSignatureHeader,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
                .isTrue();
        assertThat(WebhookSigner.verify("whsec-1", PAYLOAD.replace("d-1", "d-2"),
                transport.lastSignatureHeader,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
                .isFalse();
    }

    @Test
    @DisplayName("a failing endpoint is retried on a growing delay and then given up on")
    void retriesThenGivesUp() {
        subscribe("approval.approved");
        service.publish("c1", event("e-1"));
        transport.answerWith(WebhookTransport.Result.of(503));

        // First attempt, then one per scheduled retry.
        assertThat(service.dispatchDue()).isEqualTo(1);
        assertThat(only().attempts()).isEqualTo(1);
        assertThat(only().nextAttemptAt())
                .isEqualTo(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                        .plusSeconds(30));

        assertThat(service.dispatchDue())
                .as("nothing is due yet; the backoff is not advisory")
                .isZero();

        clock.advance(Duration.ofSeconds(30));
        assertThat(service.dispatchDue()).isEqualTo(1);
        assertThat(only().attempts()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.dispatchDue()).isEqualTo(1);
        assertThat(only().attempts()).isEqualTo(3);

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.dispatchDue()).isEqualTo(1);

        WebhookDeliveryRow dead = only();
        assertThat(dead.attempts()).isEqualTo(4);
        assertThat(dead.isDead()).isTrue();
        assertThat(dead.deadAt()).isNotNull();
        assertThat(dead.nextAttemptAt()).isNull();
        assertThat(dead.lastStatusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("a dead delivery is never retried again, however long the dispatcher runs")
    void deadStaysDead() {
        subscribe("approval.approved");
        service.publish("c1", event("e-1"));
        transport.answerWith(WebhookTransport.Result.of(500));

        for (int pass = 0; pass < 20; pass++) {
            service.dispatchDue();
            clock.advance(Duration.ofHours(1));
        }

        assertThat(only().isDead()).isTrue();
        assertThat(only().attempts()).isEqualTo(backoff.maxAttempts());
        assertThat(transport.calls)
                .as("the attempt budget is the whole budget")
                .isEqualTo(backoff.maxAttempts());
    }

    @Test
    @DisplayName("an unreachable endpoint counts as a failure rather than breaking the pass")
    void connectionFailureIsJustAFailure() {
        subscribe("approval.approved");
        service.publish("c1", event("e-1"));
        transport.answerWith(WebhookTransport.Result.failed("ConnectException: refused"));

        service.dispatchDue();

        assertThat(only().attempts()).isEqualTo(1);
        assertThat(only().lastStatusCode()).isNull();
        assertThat(only().lastError()).contains("refused");
    }

    @Test
    @DisplayName("retiring a subscription cancels its queue rather than marking it dead")
    void retiredSubscriptionCancels() {
        WebhookSubscriptionRow subscription = subscribe("approval.approved");
        service.publish("c1", event("e-1"));
        service.retire(subscription.id());

        assertThat(service.dispatchDue()).isZero();

        WebhookDeliveryRow cancelled = only();
        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.isDead())
                .as("the receiver did not fail; we stopped")
                .isFalse();
        assertThat(cancelled.lastError()).contains("retired");
        assertThat(transport.calls).isZero();
    }

    private WebhookSubscriptionRow subscribe(String eventType) {
        return service.subscribe("c1", "https://client.example/hooks", "whsec-1",
                "client system", Immutables.setOf(eventType), "master-1");
    }

    private static WebhookEvent event(String id) {
        return new WebhookEvent(id, "approval.approved", PAYLOAD);
    }

    private WebhookDeliveryRow only() {
        assertThat(deliveries.byId).hasSize(1);
        return deliveries.byId.values().iterator().next();
    }

    /** Stores the secret as-is. A real installation encrypts it under its own key. */
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

    private static final class RecordingTransport implements WebhookTransport {

        private Result answer = Result.of(200);
        private int calls;
        private String lastSignatureHeader;

        void answerWith(Result result) {
            this.answer = result;
        }

        @Override
        public Result post(String url, String payload, String signatureHeader) {
            calls++;
            lastSignatureHeader = signatureHeader;
            return answer;
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
