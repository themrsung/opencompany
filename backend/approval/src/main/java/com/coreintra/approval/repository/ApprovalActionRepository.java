package com.coreintra.approval.repository;

import com.coreintra.approval.entity.ApprovalActionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalActionRepository extends JpaRepository<ApprovalActionEntity, String> {

    /**
     * The trail for a document's steps.
     *
     * <p>Ordered by business date then offset — the canonical business ordering
     * from ADR 0002, never the derived absolute timestamp, which would reorder a
     * 26:00 action before a next-day -02:00 one.
     */
    List<ApprovalActionEntity> findByStepIdInOrderByActedAtBusinessDateAscActedAtOffsetSecondsAsc(
            Collection<String> stepIds);
}
