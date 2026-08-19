package com.coreintra.runtime.webhook;

import java.io.Serializable;
import java.time.Duration;

/**
 * Exponential, capped, and bounded by an attempt limit.
 *
 * <p>Three properties, and the third is the one that matters: a delivery that
 * retries forever is a queue that never drains and an endpoint that never
 * learns it is broken. Giving up is honest, so the ceiling here is deliberate
 * rather than a tuning constant - {@code webhook_delivery} carries a matching
 * CHECK so the rule survives a direct SQL write too.
 *
 * <p>The cap exists for the opposite reason: without it the eighth attempt of a
 * one-minute base lands two hours out, and an endpoint that came back an hour
 * ago sits idle waiting for a schedule computed while it was down.
 */
public final class Backoff implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The schema allows at most 12; eight attempts over ~2 hours is the default. */
    public static final int MAX_ATTEMPTS_CEILING = 12;

    private static final Backoff DEFAULT =
            new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(30), 8);

    private final Duration base;
    private final Duration cap;
    private final int maxAttempts;

    public Backoff(Duration base, Duration cap, int maxAttempts) {
        if (base == null || base.isZero() || base.isNegative()) {
            throw new IllegalArgumentException("a backoff needs a positive base delay");
        }
        if (cap == null || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("the backoff cap cannot be shorter than the base delay");
        }
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_CEILING) {
            throw new IllegalArgumentException(
                    "attempts must be between 1 and " + MAX_ATTEMPTS_CEILING
                            + "; retrying forever is not a delivery policy, it is a leak");
        }
        this.base = base;
        this.cap = cap;
        this.maxAttempts = maxAttempts;
    }

    public static Backoff defaults() {
        return DEFAULT;
    }

    public Duration base() {
        return base;
    }

    public Duration cap() {
        return cap;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * How long to wait after {@code attemptsSoFar} failures.
     *
     * <p>Doubling is computed by shifting a long rather than by multiplying
     * Durations, because {@code base * 2^11} overflows nothing here but reads
     * far worse when it does.
     *
     * @param attemptsSoFar 1 after the first failure
     */
    public Duration delayAfter(int attemptsSoFar) {
        if (attemptsSoFar < 1) {
            throw new IllegalArgumentException("a delay is computed after a failure, not before one");
        }
        int shift = Math.min(attemptsSoFar - 1, 30);
        long seconds = base.getSeconds() * (1L << shift);
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(cap) > 0 ? cap : delay;
    }

    /** True when {@code attemptsSoFar} has used up the budget. */
    public boolean isExhausted(int attemptsSoFar) {
        return attemptsSoFar >= maxAttempts;
    }
}
