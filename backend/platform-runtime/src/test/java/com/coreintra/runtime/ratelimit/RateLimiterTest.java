package com.coreintra.runtime.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T09:00:00Z"));
    private final FakePolicies policies = new FakePolicies();
    private final RateLimiter limiter = new RateLimiter(policies, clock, 60L);

    @Test
    @DisplayName("a refusal is a value carrying retryAfter, not an exception")
    void refusalIsAValue() {
        policy(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read", 2, Duration.ofMinutes(1));

        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read").isAllowed())
                .isTrue();
        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read").isAllowed())
                .isTrue();

        RateLimitDecision refused =
                limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read");

        assertThat(refused.isAllowed()).isFalse();
        assertThat(refused.limit()).isEqualTo(2);
        assertThat(refused.remaining()).isZero();
        assertThat(refused.retryAfter())
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("the budget comes back when the window rolls over")
    void windowRollsOver() {
        policy(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read", 1, Duration.ofMinutes(1));

        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read").isAllowed())
                .isTrue();
        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read").isAllowed())
                .isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read").isAllowed())
                .as("a fixed window; the budget returns whole rather than sliding")
                .isTrue();
    }

    @Test
    @DisplayName("one caller spending its budget does not refuse another")
    void subjectsAreIndependent() {
        policy(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read", 1, Duration.ofMinutes(1));

        limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read");
        limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read");

        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-2", "api.read").isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("buckets are counted apart, so a heavy export does not close ordinary reads")
    void bucketsAreIndependent() {
        policy(RateLimitPolicyRow.ACCOUNT, "account-1", "hr.export", 1, Duration.ofMinutes(1));

        limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "hr.export");

        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "hr.export").isAllowed())
                .isFalse();
        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "account-1", "api.read").isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("a policy for one subject is preferred to the default for its kind")
    void mostSpecificPolicyWins() {
        policy(RateLimitPolicyRow.DEFAULT, null, "api.read", 5, Duration.ofMinutes(1));
        policy(RateLimitPolicyRow.ACCOUNT, "noisy", "api.read", 1, Duration.ofMinutes(1));

        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "noisy", "api.read").limit())
                .isEqualTo(1);
        assertThat(limiter.check(RateLimitPolicyRow.ACCOUNT, "quiet", "api.read").limit())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("with no policy configured at all, the built-in default applies")
    void hasABuiltInDefault() {
        assertThat(limiter.check(RateLimitPolicyRow.API_KEY, "key-1", "api.read").limit())
                .isEqualTo(RateLimiter.DEFAULT_PERMITS);
    }

    @Test
    @DisplayName("a policy of zero permits cannot be written; that is a permission decision")
    void zeroIsNotALimit() {
        try {
            new RateLimitPolicyRow("p", "c1", RateLimitPolicyRow.ACCOUNT, "account-1",
                    "api.read", 0, Duration.ofMinutes(1),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            throw new AssertionError("a zero-permit policy should be refused");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("closed door");
        }
    }

    private void policy(String subjectKind, String subjectId, String limitKey, int permits,
                        Duration window) {
        policies.save(new RateLimitPolicyRow("policy-" + policies.rows.size(), "c1", subjectKind,
                subjectId, limitKey, permits, window,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        // The limiter caches policies; move past the refresh interval so the
        // test is exercising the same reload path production uses.
        clock.advance(Duration.ofSeconds(61));
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

    private static final class FakePolicies implements RateLimitPolicyRepository {

        private final Map<String, RateLimitPolicyRow> rows =
                new LinkedHashMap<String, RateLimitPolicyRow>();

        @Override
        public RateLimitPolicyRow save(RateLimitPolicyRow row) {
            rows.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<RateLimitPolicyRow> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<RateLimitPolicyRow> findBySubjectKindAndSubjectIdAndLimitKeyAndRetiredAtIsNull(
                String subjectKind, String subjectId, String limitKey) {
            for (RateLimitPolicyRow row : rows.values()) {
                if (row.isLive() && row.subjectKind().equals(subjectKind)
                        && subjectId.equals(row.subjectId()) && row.limitKey().equals(limitKey)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<RateLimitPolicyRow> findBySubjectKindAndSubjectIdIsNullAndLimitKeyAndRetiredAtIsNull(
                String subjectKind, String limitKey) {
            for (RateLimitPolicyRow row : rows.values()) {
                if (row.isLive() && row.subjectKind().equals(subjectKind)
                        && row.subjectId() == null && row.limitKey().equals(limitKey)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<RateLimitPolicyRow> findByRetiredAtIsNull() {
            List<RateLimitPolicyRow> live = new ArrayList<RateLimitPolicyRow>();
            for (RateLimitPolicyRow row : rows.values()) {
                if (row.isLive()) {
                    live.add(row);
                }
            }
            return live;
        }
    }
}
