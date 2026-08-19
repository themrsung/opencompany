package com.coreintra.runtime.idempotency;

import org.springframework.data.repository.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Records are expired, never edited away.
 *
 * <p>{@link #deleteByExpiresAtBefore} is the one delete in this module, and it
 * is deliberate: an idempotency record is a receipt with a stated lifetime, not
 * a trail. It says so in the row - {@code expires_at} is written at insert -
 * so a sweep removes only rows that already stopped meaning anything.
 */
public interface IdempotencyKeyRepository extends Repository<IdempotencyKeyRow, String> {

    IdempotencyKeyRow save(IdempotencyKeyRow row);

    Optional<IdempotencyKeyRow> findById(String id);

    Optional<IdempotencyKeyRow> findByAccountIdAndIdempotencyKey(String accountId, String idempotencyKey);

    List<IdempotencyKeyRow> findByCompanyIdAndExpiresAtAfter(String companyId, OffsetDateTime now);

    int deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
