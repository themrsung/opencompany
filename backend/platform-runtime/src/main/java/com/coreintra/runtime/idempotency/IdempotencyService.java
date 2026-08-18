package com.coreintra.runtime.idempotency;

import com.coreintra.compat.Texts;
import com.coreintra.runtime.crypto.Digests;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes a retry safe to answer from here (§10).
 *
 * <p>Every POST that creates money or approvals carries a key. The endpoint
 * calls {@link #begin} first: it either gets permission to run, or the answer
 * from the run that already happened.
 *
 * <h2>Why {@code REQUIRES_NEW}</h2>
 *
 * <p>The reservation has to survive the failure of the work it guards, and the
 * replay has to be readable while that work is still in flight. Both need this
 * row committed independently of the caller's transaction. It also means a
 * caller that rolls back leaves an {@code IN_FLIGHT} row behind - deliberately.
 * The row expires; until then a duplicate is refused rather than run twice,
 * which is the safer of the two mistakes for a payment.
 */
@Service
public class IdempotencyService {

    /** Long enough for a client to have finished retrying, short enough to sweep. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    public static final String KEY_REUSED = "idempotency_key_reused";
    public static final String IN_FLIGHT = "idempotency_request_in_flight";

    private final IdempotencyKeyRepository records;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    /**
     * Reserves a key, or reports what happened last time it was used.
     *
     * @throws IdempotencyConflictException if the key was used for a different
     *         body, or a request under it has not finished
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyDecision begin(String companyId, String accountId, String idempotencyKey,
                                     String endpoint, String requestBody) {
        return begin(companyId, accountId, idempotencyKey, endpoint, requestBody, DEFAULT_RETENTION);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyDecision begin(String companyId, String accountId, String idempotencyKey,
                                     String endpoint, String requestBody,
                                     Duration retention) {
        if (Texts.isBlank(companyId) || Texts.isBlank(accountId)) {
            throw new IllegalArgumentException("an idempotency key belongs to a caller in a company");
        }
        if (Texts.isBlank(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "an idempotency key cannot be blank; a POST that creates money or "
                            + "approvals must carry one (§10)");
        }
        String key = Texts.strip(idempotencyKey);
        String hash = Digests.sha256Hex(requestBody);
        OffsetDateTime now = OffsetDateTime.now(clock);

        Optional<IdempotencyKeyRow> seen = records.findByAccountIdAndIdempotencyKey(accountId, key);
        if (seen.isPresent()) {
            return decide(seen.get(), key, hash);
        }
        try {
            IdempotencyKeyRow reserved = records.save(new IdempotencyKeyRow(
                    UUID.randomUUID().toString(), companyId, accountId, key, endpoint, hash,
                    now, now.plus(retention == null ? DEFAULT_RETENTION : retention)));
            return IdempotencyDecision.proceed(reserved.id());
        } catch (DataIntegrityViolationException raced) {
            // The other half of the race committed between the read and the insert.
            // The unique index is the arbiter; this branch just reads its verdict.
            IdempotencyKeyRow winner = records.findByAccountIdAndIdempotencyKey(accountId, key)
                    .orElseThrow(() -> raced);
            return decide(winner, key, hash);
        }
    }

    private IdempotencyDecision decide(IdempotencyKeyRow existing, String key, String hash) {
        if (!existing.matches(hash)) {
            throw new IdempotencyConflictException(KEY_REUSED, key,
                    "idempotency key \"" + key + "\" was already used for a different request "
                            + "body. A key identifies one intent; reusing it for another is "
                            + "refused rather than silently answered with the first result.");
        }
        if (!existing.isCompleted()) {
            throw new IdempotencyConflictException(IN_FLIGHT, key,
                    "a request under idempotency key \"" + key + "\" is still in flight. "
                            + "Retry after it completes; its response will be replayed.");
        }
        return IdempotencyDecision.replay(
                existing.id(), existing.responseStatus(), existing.responseBody());
    }

    /**
     * Stores the answer so every later retry gets it.
     *
     * <p>Also in {@code REQUIRES_NEW}: the response is a fact about work that
     * has already committed, and losing it to an unrelated rollback would leave
     * a row that can never be answered.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String recordId, int status, String responseBody) {
        IdempotencyKeyRow row = records.findById(recordId).orElseThrow(
                () -> new IllegalArgumentException("no such idempotency record: " + recordId));
        row.complete(status, responseBody, OffsetDateTime.now(clock));
        records.save(row);
    }

    /** Removes records whose stated lifetime has passed. */
    @Transactional
    public int purgeExpired() {
        return records.deleteByExpiresAtBefore(OffsetDateTime.now(clock));
    }
}
