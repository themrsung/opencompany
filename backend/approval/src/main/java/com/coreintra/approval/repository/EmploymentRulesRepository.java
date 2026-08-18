package com.coreintra.approval.repository;

import com.coreintra.approval.entity.EmploymentRulesEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmploymentRulesRepository extends JpaRepository<EmploymentRulesEntity, String> {

    /**
     * Every version a company has published, oldest first.
     *
     * <p>Ordered by version rather than by {@code effectiveFrom}: two
     * amendments can share an effective date, and the version number is the
     * thing employees and inspectors cite.
     */
    List<EmploymentRulesEntity> findByCompanyIdOrderByVersionAsc(String companyId);
}
