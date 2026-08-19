package com.coreintra.approval.repository;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalDocumentRepository extends JpaRepository<ApprovalDocumentEntity, String> {

    /**
     * The approval inbox: documents waiting on this account, oldest first.
     *
     * <p>One of the two screens that decide whether people like this product, so
     * it is a single indexed query rather than a fetch-then-filter. Ordering is
     * by business date then offset — the canonical business ordering, never the
     * derived absolute timestamp and never the wire string.
     */
    @Query("select d from ApprovalDocumentEntity d "
            + "where d.state in :activeStates "
            + "and exists (select 1 from ApprovalStepEntity s "
            + "            where s.documentId = d.id "
            + "            and s.state = com.coreintra.approval.domain.ApprovalStep$StepState.PENDING "
            + "            and exists (select 1 from ApprovalStepApproverEntity a "
            + "                        where a.stepId = s.id and a.accountId = :accountId)) "
            + "order by d.submittedAt.businessDate asc, d.submittedAt.offsetSeconds asc")
    List<ApprovalDocumentEntity> findInbox(@Param("accountId") String accountId,
            @Param("activeStates") Collection<ApprovalState> activeStates, Pageable pageable);

    /** Documents this account drafted, for the "내가 올린 문서" view. */
    List<ApprovalDocumentEntity> findByDrafterAccountIdOrderByCreatedAtDesc(String drafterAccountId);

    List<ApprovalDocumentEntity> findByCompanyIdAndState(String companyId, ApprovalState state);
}
