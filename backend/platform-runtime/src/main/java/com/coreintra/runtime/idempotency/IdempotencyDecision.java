package com.coreintra.runtime.idempotency;

import java.io.Serializable;

/**
 * What the caller should do with this request.
 *
 * <p>Two outcomes, and the third is an exception rather than a value: a
 * conflict is not a state the endpoint can carry on from, whereas "run it" and
 * "you already ran it, here is the answer" both are.
 */
public final class IdempotencyDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String recordId;
    private final boolean replay;
    private final Integer status;
    private final String body;

    private IdempotencyDecision(String recordId, boolean replay, Integer status, String body) {
        this.recordId = recordId;
        this.replay = replay;
        this.status = status;
        this.body = body;
    }

    static IdempotencyDecision proceed(String recordId) {
        return new IdempotencyDecision(recordId, false, null, null);
    }

    static IdempotencyDecision replay(String recordId, Integer status, String body) {
        return new IdempotencyDecision(recordId, true, status, body);
    }

    /** The row that reserves this key. Pass it back to {@code complete}. */
    public String recordId() {
        return recordId;
    }

    /** True when the work was already done and the stored response should be returned. */
    public boolean isReplay() {
        return replay;
    }

    public Integer status() {
        return status;
    }

    public String body() {
        return body;
    }
}
