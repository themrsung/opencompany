package com.coreintra.documents.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentFieldValueId;

/**
 * The reporting access path: field values without opening forty thousand ZIPs.
 *
 * <p>The typed finders exist so a caller cannot accidentally compare money as text.
 * A MONEY question is asked of {@code valueAmount}; a DATE question of {@code valueDate}.
 */
public interface DocumentFieldValueRepository
        extends JpaRepository<DocumentFieldValueEntity, DocumentFieldValueId> {

    /** Everything extracted from one version. The projection, rebuilt on every write. */
    List<DocumentFieldValueEntity> findByDocumentIdAndVersionNo(String documentId, Integer versionNo);

    /** "Which documents have field X = v", exactly. */
    List<DocumentFieldValueEntity> findByFieldIdAndValueText(String fieldId, String valueText);

    /** "Every expense over 5,000,000 last quarter", as a comparison rather than a guess. */
    List<DocumentFieldValueEntity> findByFieldIdAndValueAmountGreaterThanEqual(
            String fieldId, BigDecimal amount);

    /** Which documents point at an employee or an org unit. */
    List<DocumentFieldValueEntity> findByFieldIdAndValueRefId(String fieldId, String refId);

    // There is deliberately no delete. A version is immutable, so its extracted values are
    // too: correcting a value means the next version, and a delete method here would be
    // the tool somebody reached for instead.
}
