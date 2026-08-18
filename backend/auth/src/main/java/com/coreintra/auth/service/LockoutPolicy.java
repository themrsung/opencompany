package com.coreintra.auth.service;

import java.time.Duration;

/**
 * Progressive delay after failed sign-in attempts.
 *
 * <p>A six-digit code has a million possibilities and stays valid for up to
 * three time steps under the default drift. Unthrottled, that is guessable.
 * Throttled this way, an attacker gets a handful of attempts per hour.
 *
 * <h2>Why delay rather than a hard lock</h2>
 *
 * <p>A hard lock hands anyone who knows a username a denial-of-service against
 * that account: they simply fail five times and the real user is locked out.
 * Escalating delay costs an attacker everything and the legitimate user — who
 * fails once or twice, not fifteen times — very little.
 *
 * <p>Delays double from the third failure and cap at fifteen minutes. The first
 * two failures are free because mistyping a code is normal.
 */
public final class LockoutPolicy {

    private static final int FREE_ATTEMPTS = 2;
    private static final Duration BASE_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_DELAY = Duration.ofMinutes(15);

    /** How long recent failures are counted for. */
    public static final Duration WINDOW = Duration.ofHours(1);

    private LockoutPolicy() {
    }

    /**
     * How long the account must wait before the next attempt is accepted.
     *
     * @param recentFailures failures within {@link #WINDOW}
     * @return {@link Duration#ZERO} when the next attempt may proceed now
     */
    public static Duration delayAfter(int recentFailures) {
        if (recentFailures <= FREE_ATTEMPTS) {
            return Duration.ZERO;
        }
        int escalations = recentFailures - FREE_ATTEMPTS;
        // Cap the exponent before shifting: 1 << 40 overflows into nonsense,
        // and a negative delay would wave every attempt straight through.
        int shift = Math.min(escalations - 1, 20);
        long seconds = BASE_DELAY.getSeconds() << shift;
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /** True when an attempt should be refused without even checking the code. */
    public static boolean isThrottled(int recentFailures, Duration sinceLastFailure) {
        Duration required = delayAfter(recentFailures);
        return !required.isZero() && sinceLastFailure.compareTo(required) < 0;
    }
}
