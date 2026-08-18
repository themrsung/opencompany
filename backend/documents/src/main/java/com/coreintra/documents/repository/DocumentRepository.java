package com.coreintra.documents.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentEntity;

/** Document heads. Every byte they have held is in {@code document_version}. */
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    List<DocumentEntity> findByCompanyIdAndRetiredAtIsNull(String companyId);

    List<DocumentEntity> findByCompanyIdAndDocumentTypeAndRetiredAtIsNull(
            String companyId, String documentType);

    /** Which documents were drafted from a template version: the impact list before a change. */
    List<DocumentEntity> findByTemplateIdAndTemplateVersionNo(String templateId, Integer versionNo);
}
