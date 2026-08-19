package com.coreintra.runtime.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffTest {

    private final Backoff backoff = new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 8);

    @Test
    @DisplayName("the delay doubles after each failure")
    void doubles() {
        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(backoff.delayAfter(4)).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("the delay never exceeds the cap, however many attempts have failed")
    void isCapped() {
        for (int attempt = 1; attempt <= Backoff.MAX_ATTEMPTS_CEILING; attempt++) {
            assertThat(backoff.delayAfter(attempt))
                    .as("delay after attempt " + attempt)
                    .isLessThanOrEqualTo(backoff.cap());
        }
        assertThat(backoff.delayAfter(7)).isEqualTo(Duration.ofMinutes(30));
        assertThat(backoff.delayAfter(8)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("the total wait before giving up is bounded")
    void totalIsBounded() {
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < backoff.maxAttempts(); attempt++) {
            total = total.plus(backoff.delayAfter(attempt));
        }
        assertThat(total)
                .as("a queue that drains within a working day, not one that never gives up")
                .isLessThan(Duration.ofHours(4));
    }

    @Test
    @DisplayName("the budget is exhausted at the attempt limit and not before")
    void exhaustion() {
        assertThat(backoff.isExhausted(7)).isFalse();
        assertThat(backoff.isExhausted(8)).isTrue();
        assertThat(backoff.isExhausted(9)).isTrue();
    }

    @Test
    @DisplayName("a policy that would retry forever cannot be constructed")
    void refusesUnboundedPolicies() {
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a delivery policy");
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofMinutes(30), 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofMinutes(5), Duration.ofMinutes(1), 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a delay is only asked for after a failure")
    void noDelayBeforeTheFirstAttempt() {
        assertThatThrownBy(() -> backoff.delayAfter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
