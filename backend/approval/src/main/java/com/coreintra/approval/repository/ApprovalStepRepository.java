package com.coreintra.approval.repository;

import com.coreintra.approval.entity.ApprovalStepEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStepEntity, String> {
    List<ApprovalStepEntity> findByDocumentIdOrderByPositionAsc(String documentId);
}
