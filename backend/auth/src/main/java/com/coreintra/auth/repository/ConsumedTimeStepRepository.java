package com.coreintra.auth.repository;

import com.coreintra.auth.entity.ConsumedTimeStep;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumedTimeStepRepository
        extends JpaRepository<ConsumedTimeStep, ConsumedTimeStep.Key> {

    /**
     * Deletes counters too old to be replayable.
     *
     * <p>Retention is bounded by the drift window, not by policy: a step outside
     * it can no longer be accepted, so remembering it protects nothing and only
     * grows the table.
     */
    @Modifying
    @Query("delete from ConsumedTimeStep c where c.consumedAt < :before")
    int deleteConsumedBefore(@Param("before") OffsetDateTime before);
}
