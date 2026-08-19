package com.coreintra.approval.repository;

import com.coreintra.approval.entity.CompanyRepresentationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepresentationRepository
        extends JpaRepository<CompanyRepresentationEntity, String> {

    /**
     * Every arrangement a company has ever had, newest first.
     *
     * <p>Deliberately not "the row covering this date": the intervals are
     * half-open and a company has a handful of them in its lifetime, so
     * selecting the covering one in Java keeps the boundary rule in one place
     * next to the test that pins it, rather than in a WHERE clause whose
     * off-by-one nobody would notice until a document was routed under the wrong
     * mode.
     */
    List<CompanyRepresentationEntity> findByCompanyIdOrderByEffectiveFromDesc(String companyId);
}
