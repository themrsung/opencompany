package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentTemplateEntity;

/** Template families. The versions are where the bytes and the manifest live. */
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplateEntity, String> {

    /** The picker: what a drafter may start from, for one kind of document. */
    List<DocumentTemplateEntity> findByCompanyIdAndDocumentTypeAndRetiredAtIsNullAndActiveTrue(
            String companyId, String documentType);

    List<DocumentTemplateEntity> findByCompanyIdAndRetiredAtIsNull(String companyId);

    /** The seeded-template lookup: a code is stable across a rename, and a name is not. */
    Optional<DocumentTemplateEntity> findByCompanyIdAndCode(String companyId, String code);
}
