package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.DocumentTemplateBodyEntity;
import com.coreintra.documents.entity.TemplateBodyId;

/** The Korean and English bodies of a published version. */
public interface DocumentTemplateBodyRepository
        extends JpaRepository<DocumentTemplateBodyEntity, TemplateBodyId> {

    List<DocumentTemplateBodyEntity> findByTemplateIdAndVersionNo(String templateId, Integer versionNo);

    Optional<DocumentTemplateBodyEntity> findByTemplateIdAndVersionNoAndLocale(
            String templateId, Integer versionNo, String locale);
}
