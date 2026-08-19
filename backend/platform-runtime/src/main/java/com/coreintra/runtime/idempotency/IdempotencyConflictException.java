package com.coreintra.runtime.idempotency;

/**
 * The key has been seen before and this is not the request it was used for.
 *
 * <p>Two shapes reach here: a different body under the same key, and a second
 * request arriving while the first is still running. Both are refused, and the
 * API layer turns this into an RFC 7807 problem+json with a machine-readable
 * {@code code} (§10). Answering either one with the first request's result
 * would be worse than an error, because the caller would never learn that its
 * second request did not happen.
 */
public class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String idempotencyKey;

    public IdempotencyConflictException(String code, String idempotencyKey, String message) {
        super(message);
        this.code = code;
        this.idempotencyKey = idempotencyKey;
    }

    /** {@code idempotency_key_reused} or {@code idempotency_request_in_flight}. */
    public String code() {
        return code;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
