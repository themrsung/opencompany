package com.coreintra.approval.repository;

import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalTemplateStepRepository
        extends JpaRepository<ApprovalTemplateStepEntity, String> {

    /**
     * The steps of several templates in one query.
     *
     * <p>By id collection rather than one template at a time, because the caller
     * always has the whole candidate set in hand: the company default and the
     * unit-specific ones are read together, and issuing one query per candidate
     * would make template resolution N+1 on the submission path.
     */
    List<ApprovalTemplateStepEntity> findByTemplateIdInOrderByPositionAsc(
            Collection<String> templateIds);

    List<ApprovalTemplateStepEntity> findByTemplateIdOrderByPositionAsc(String templateId);
}
