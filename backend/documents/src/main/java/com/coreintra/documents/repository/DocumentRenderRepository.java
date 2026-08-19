package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.RenderFormat;

/** Archived renders, keyed by everything that could change the bytes. */
public interface DocumentRenderRepository extends JpaRepository<DocumentRenderEntity, String> {

    /**
     * The cache hit and the reproducibility check, in one lookup.
     *
     * <p>Same document, same version, same format, same config: the archived bytes are
     * the answer, and re-rendering could only differ.
     */
    Optional<DocumentRenderEntity> findByDocumentIdAndVersionNoAndFormatAndConfigFingerprint(
            String documentId, Integer versionNo, RenderFormat format, String configFingerprint);

    List<DocumentRenderEntity> findByDocumentIdAndVersionNo(String documentId, Integer versionNo);

    Optional<DocumentRenderEntity> findByOutputBlobSha256(String outputBlobSha256);

    /**
     * How many archived renders were made with a given font family.
     *
     * <p>The number shown before a font is removed. It counts renders rather than
     * documents deliberately: a document only proves it needs a font once something has
     * tried to render it, and an archived PDF is the artefact that would stop being
     * reproducible.
     */
    long countByFontSetContaining(String familyFragment);
}
