package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.ApprovalSnapshotEntity;

/** What was approved, per approval action. Append-only: there is no delete and no update. */
public interface ApprovalSnapshotRepository extends JpaRepository<ApprovalSnapshotEntity, String> {

    Optional<ApprovalSnapshotEntity> findByApprovalActionId(String approvalActionId);

    /** The whole trail for one version, in the order it was written. */
    List<ApprovalSnapshotEntity> findByDocumentIdAndVersionNoOrderByCreatedAtAsc(String documentId,
            Integer versionNo);

    List<ApprovalSnapshotEntity> findByDocumentIdOrderByCreatedAtAsc(String documentId);

    /** Approvals recorded while the conversion worker was down. An operations queue. */
    List<ApprovalSnapshotEntity> findByPdfBlobSha256IsNull();
}
