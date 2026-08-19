package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.entity.DocumentVersionId;

/**
 * Version rows. Append-only by convention and by the absence of any setter to break it.
 */
public interface DocumentVersionRepository
        extends JpaRepository<DocumentVersionEntity, DocumentVersionId> {

    /** Newest first. The history panel, and the source of the next version number. */
    List<DocumentVersionEntity> findByDocumentIdOrderByVersionNoDesc(String documentId);

    Optional<DocumentVersionEntity> findFirstByDocumentIdOrderByVersionNoDesc(String documentId);

    Optional<DocumentVersionEntity> findByDocumentIdAndVersionNo(String documentId, Integer versionNo);

    /** Every version that carries these exact bytes. Deduplication is a property, not a bug. */
    List<DocumentVersionEntity> findByBlobSha256(String blobSha256);
}
