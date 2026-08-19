package com.coreintra.approval.repository;

import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalStepApproverRepository
        extends JpaRepository<ApprovalStepApproverEntity, ApprovalStepApproverEntity.Key> {

    List<ApprovalStepApproverEntity> findByStepIdIn(Collection<String> stepIds);
}
