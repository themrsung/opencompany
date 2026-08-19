package com.coreintra.runtime.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The outbox. Rows are relayed, never removed.
 *
 * <p>An outbox that deletes what it relayed cannot answer "did you ever send
 * this?", which is the question the outbox exists to make answerable.
 */
public interface OutboxEventRepository extends Repository<OutboxEventRow, String> {

    OutboxEventRow save(OutboxEventRow row);

    Optional<OutboxEventRow> findById(String id);

    /** The relay's queue, oldest first so events reach a client in order. */
    List<OutboxEventRow> findByRelayedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    List<OutboxEventRow> findByCompanyIdAndResourceAndResourceIdOrderByCreatedAtDesc(
            String companyId, String resource, String resourceId);
}
