package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.entity.TemplateVersionId;

/** Published template versions: immutable, and each with its own field manifest. */
public interface DocumentTemplateVersionRepository
        extends JpaRepository<DocumentTemplateVersionEntity, TemplateVersionId> {

    /** Newest first: the version-compare UI walks this list. */
    List<DocumentTemplateVersionEntity> findByTemplateIdOrderByVersionNoDesc(String templateId);

    Optional<DocumentTemplateVersionEntity> findFirstByTemplateIdOrderByVersionNoDesc(String templateId);
}
