package com.coreintra.documents.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.SignatureImpressionUseEntity;

/** Append-only: every occasion a seal left the store. */
public interface SignatureImpressionUseRepository
        extends JpaRepository<SignatureImpressionUseEntity, String> {

    /** "Where has my seal been used?" - the question this table exists to answer. */
    List<SignatureImpressionUseEntity> findBySignatureImageIdOrderByUsedAtDesc(String signatureImageId);

    List<SignatureImpressionUseEntity> findByDocumentIdAndVersionNo(String documentId, Integer versionNo);
}
