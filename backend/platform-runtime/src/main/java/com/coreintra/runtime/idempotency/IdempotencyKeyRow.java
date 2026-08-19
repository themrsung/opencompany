package com.coreintra.runtime.idempotency;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * One idempotency key, in one caller's key space.
 *
 * <p>The row is the lock. Two retries arriving together both try to insert it
 * and exactly one wins, which is why the unique index in
 * {@code V9__platform_runtime.sql} is load-bearing and a check-then-insert in
 * a service would not be.
 *
 * <h2>Why the request hash is stored</h2>
 *
 * <p>A key identifies an intent, not a request. The same key with a different
 * body is not a retry; it is a second request wearing the first one's name.
 * Answering it with the first one's result would be a silent, undetectable
 * data loss, so it is refused instead - and the only way to tell the two apart
 * later is to have kept the hash.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyRow {

    public static final String IN_FLIGHT = "IN_FLIGHT";
    public static final String COMPLETED = "COMPLETED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    /**
     * The caller. Two clients may pick the same key and must not collide.
     *
     * <p>One column covers both a person and an integration, because a service
     * account is an account: the key space follows whoever authenticated.
     */
    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** Method and path together, e.g. {@code POST /api/v1/vouchers}. */
    @Column(name = "endpoint", nullable = false, length = 400)
    private String endpoint;

    /** SHA-256 hex of the request body. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected IdempotencyKeyRow() {
    }

    public IdempotencyKeyRow(String id, String companyId, String accountId, String idempotencyKey,
                             String endpoint, String requestHash,
                             OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.id = id;
        this.companyId = companyId;
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.state = IN_FLIGHT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String accountId() {
        return accountId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String endpoint() {
        return endpoint;
    }

    public String requestHash() {
        return requestHash;
    }

    public String state() {
        return state;
    }

    public boolean isCompleted() {
        return COMPLETED.equals(state);
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime completedAt() {
        return completedAt;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    /**
     * Records the answer, once.
     *
     * <p>A completed record is not rewritten by a later attempt: whoever
     * finished first is what every retry now returns. Allowing an overwrite
     * would let a duplicate quietly change an answer a client already acted on.
     */
    public void complete(int status, String body, OffsetDateTime at) {
        if (isCompleted()) {
            return;
        }
        this.responseStatus = status;
        this.responseBody = body;
        this.completedAt = at;
        this.state = COMPLETED;
    }

    /** True when this record answers the request now being made. */
    public boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }
}
