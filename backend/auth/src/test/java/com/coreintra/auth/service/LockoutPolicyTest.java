package com.coreintra.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LockoutPolicyTest {

    @Test
    @DisplayName("the first two failures are free, because mistyping a code is normal")
    void firstFailuresAreFree() {
        assertThat(LockoutPolicy.delayAfter(0)).isZero();
        assertThat(LockoutPolicy.delayAfter(1)).isZero();
        assertThat(LockoutPolicy.delayAfter(2)).isZero();
    }

    @Test
    @DisplayName("delay escalates from the third failure")
    void delayEscalates() {
        assertThat(LockoutPolicy.delayAfter(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(LockoutPolicy.delayAfter(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(LockoutPolicy.delayAfter(5)).isEqualTo(Duration.ofSeconds(8));
        assertThat(LockoutPolicy.delayAfter(6)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("the delay caps at fifteen minutes and never overflows")
    void capsAndNeverOverflows() {
        assertThat(LockoutPolicy.delayAfter(20)).isEqualTo(Duration.ofMinutes(15));
        // Without the shift clamp, a large failure count overflows the long and
        // can produce a negative duration - which would wave every attempt
        // straight through, turning the lockout into an amplifier.
        assertThat(LockoutPolicy.delayAfter(1_000)).isEqualTo(Duration.ofMinutes(15));
        assertThat(LockoutPolicy.delayAfter(Integer.MAX_VALUE))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(LockoutPolicy.delayAfter(Integer.MAX_VALUE).isNegative()).isFalse();
    }

    @Test
    @DisplayName("an attempt is throttled until the required delay has passed")
    void throttleWindow() {
        assertThat(LockoutPolicy.isThrottled(5, Duration.ofSeconds(3)))
                .as("8s required, 3s elapsed")
                .isTrue();
        assertThat(LockoutPolicy.isThrottled(5, Duration.ofSeconds(9)))
                .as("8s required, 9s elapsed")
                .isFalse();
        assertThat(LockoutPolicy.isThrottled(1, Duration.ZERO))
                .as("below the free threshold, nothing is throttled")
                .isFalse();
    }

    @Test
    @DisplayName("a legitimate user who fails twice is never delayed")
    void legitimateUserUnaffected() {
        // The design intent, stated as a test: escalation must cost an attacker
        // everything and a fumbling user nothing.
        assertThat(LockoutPolicy.isThrottled(2, Duration.ZERO)).isFalse();
    }
}
