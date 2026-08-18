package com.coreintra.documents.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.coreintra.documents.entity.ConversionJobEntity;

/** The conversion queue. */
public interface ConversionJobRepository extends JpaRepository<ConversionJobEntity, String> {

    /** Enqueue is idempotent by this key: pressing Export twice joins the first job. */
    Optional<ConversionJobEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * The worker's claim query: oldest queued or failed job, or one whose lease has lapsed.
     *
     * <p>Ordered by creation so a job cannot starve behind newer work, and bounded by
     * {@code attemptCount} so an abandoned job is never handed out again.
     */
    @Query("select j from ConversionJobEntity j "
            + "where j.attemptCount < j.maxAttempts "
            + "and (j.state = com.coreintra.documents.entity.ConversionJobEntity$State.QUEUED "
            + "  or j.state = com.coreintra.documents.entity.ConversionJobEntity$State.FAILED "
            + "  or (j.state = com.coreintra.documents.entity.ConversionJobEntity$State.RUNNING "
            + "      and j.leaseExpiresAt < :now)) "
            + "order by j.createdAt asc")
    List<ConversionJobEntity> findClaimable(@Param("now") OffsetDateTime now, Pageable pageable);

    List<ConversionJobEntity> findByStateOrderByCreatedAtAsc(ConversionJobEntity.State state);

    List<ConversionJobEntity> findByDocumentIdAndVersionNo(String documentId, Integer versionNo);
}
