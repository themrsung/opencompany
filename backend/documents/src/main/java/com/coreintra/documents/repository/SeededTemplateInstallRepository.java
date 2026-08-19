package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.SeededTemplateInstallEntity;
import com.coreintra.documents.entity.SeededTemplateInstallId;

/** What the factory catalogue has installed, per company, and at which revision. */
public interface SeededTemplateInstallRepository
        extends JpaRepository<SeededTemplateInstallEntity, SeededTemplateInstallId> {

    List<SeededTemplateInstallEntity> findByCompanyId(String companyId);

    Optional<SeededTemplateInstallEntity> findByCompanyIdAndCode(String companyId, String code);
}
