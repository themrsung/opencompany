package com.coreintra.runtime.ratelimit;

import java.io.Serializable;
import java.time.Duration;

/**
 * The answer to "may this call proceed", carried as a value.
 *
 * <p>Refusal is not an exception. A rate limit is an expected outcome on a busy
 * morning, and it needs three numbers the caller has to put in headers -
 * {@code Retry-After}, the limit, and what is left - which an exception would
 * turn into a catch block that reconstructs them. The API layer maps a refusal
 * to 429 with those headers; nothing here knows about HTTP.
 */
public final class RateLimitDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean allowed;
    private final int limit;
    private final int remaining;
    private final Duration retryAfter;

    private RateLimitDecision(boolean allowed, int limit, int remaining, Duration retryAfter) {
        this.allowed = allowed;
        this.limit = limit;
        this.remaining = remaining;
        this.retryAfter = retryAfter;
    }

    static RateLimitDecision allowed(int limit, int remaining) {
        return new RateLimitDecision(true, limit, remaining, Duration.ZERO);
    }

    static RateLimitDecision refused(int limit, Duration retryAfter) {
        return new RateLimitDecision(false, limit, 0, retryAfter);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return remaining;
    }

    /**
     * How long to wait. Zero when allowed.
     *
     * <p>Rounded up to a whole second by the caller if it is going into a
     * {@code Retry-After} header: a client told to wait zero seconds retries
     * immediately and is refused again.
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
