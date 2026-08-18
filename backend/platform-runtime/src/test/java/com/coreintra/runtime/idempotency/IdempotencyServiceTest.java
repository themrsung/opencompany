package com.coreintra.runtime.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneOffset.UTC);

    private static final String BODY = "{\"amount\":\"1400000.25\",\"currency\":\"KRW\"}";
    private static final String OTHER_BODY = "{\"amount\":\"14000000.25\",\"currency\":\"KRW\"}";

    private final FakeRecords records = new FakeRecords();
    private final IdempotencyService service = new IdempotencyService(records, FIXED);

    @Test
    @DisplayName("the first request with a key is allowed to run")
    void firstRequestProceeds() {
        IdempotencyDecision decision = begin(BODY);

        assertThat(decision.isReplay()).isFalse();
        assertThat(decision.recordId()).isNotBlank();
    }

    @Test
    @DisplayName("a retry with the same body replays the stored response instead of running again")
    void retryReplays() {
        IdempotencyDecision first = begin(BODY);
        service.complete(first.recordId(), 201, "{\"id\":\"voucher-1\"}");

        IdempotencyDecision second = begin(BODY);

        assertThat(second.isReplay()).isTrue();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo("{\"id\":\"voucher-1\"}");
    }

    @Test
    @DisplayName("the same key with a different body is refused, not answered with the old result")
    void differentBodyIsRejectedLoudly() {
        IdempotencyDecision first = begin(BODY);
        service.complete(first.recordId(), 201, "{\"id\":\"voucher-1\"}");

        assertThatThrownBy(() -> begin(OTHER_BODY))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request body")
                .extracting(thrown -> ((IdempotencyConflictException) thrown).code())
                .isEqualTo(IdempotencyService.KEY_REUSED);
    }

    @Test
    @DisplayName("a duplicate arriving while the first is still running is refused")
    void inFlightDuplicateIsRefused() {
        begin(BODY);

        assertThatThrownBy(() -> begin(BODY))
                .isInstanceOf(IdempotencyConflictException.class)
                .extracting(thrown -> ((IdempotencyConflictException) thrown).code())
                .isEqualTo(IdempotencyService.IN_FLIGHT);
    }

    @Test
    @DisplayName("a completed response is never overwritten by a later attempt")
    void firstAnswerWins() {
        IdempotencyDecision first = begin(BODY);
        service.complete(first.recordId(), 201, "{\"id\":\"voucher-1\"}");
        service.complete(first.recordId(), 500, "{\"error\":\"late\"}");

        assertThat(begin(BODY).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("two callers may use the same key without colliding")
    void keysAreScopedToTheCaller() {
        service.begin("c1", "account-a", "key-1", "POST /api/v1/vouchers", BODY);
        IdempotencyDecision other =
                service.begin("c1", "account-b", "key-1", "POST /api/v1/vouchers", OTHER_BODY);

        assertThat(other.isReplay()).isFalse();
    }

    @Test
    @DisplayName("a POST that creates money must carry a key at all")
    void keyIsMandatory() {
        assertThatThrownBy(() ->
                service.begin("c1", "account-a", "  ", "POST /api/v1/vouchers", BODY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    private IdempotencyDecision begin(String body) {
        return service.begin("c1", "account-a", "key-1", "POST /api/v1/vouchers", body);
    }

    private static final class FakeRecords implements IdempotencyKeyRepository {

        private final Map<String, IdempotencyKeyRow> byId =
                new LinkedHashMap<String, IdempotencyKeyRow>();

        @Override
        public IdempotencyKeyRow save(IdempotencyKeyRow row) {
            byId.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<IdempotencyKeyRow> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<IdempotencyKeyRow> findByAccountIdAndIdempotencyKey(
                String accountId, String idempotencyKey) {
            for (IdempotencyKeyRow row : byId.values()) {
                if (row.accountId().equals(accountId) && row.idempotencyKey().equals(idempotencyKey)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<IdempotencyKeyRow> findByCompanyIdAndExpiresAtAfter(
                String companyId, OffsetDateTime now) {
            return new ArrayList<IdempotencyKeyRow>(byId.values());
        }

        @Override
        public int deleteByExpiresAtBefore(OffsetDateTime cutoff) {
            int before = byId.size();
            byId.values().removeIf(row -> row.expiresAt().isBefore(cutoff));
            return before - byId.size();
        }
    }
}
