package com.coreintra.approval.repository;

import com.coreintra.approval.entity.EmploymentRulesRepresentativeEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmploymentRulesRepresentativeRepository
        extends JpaRepository<EmploymentRulesRepresentativeEntity,
                EmploymentRulesRepresentativeEntity.Key> {

    List<EmploymentRulesRepresentativeEntity> findByRulesIdOrderBySortOrderAsc(String rulesId);

    List<EmploymentRulesRepresentativeEntity> findByRulesIdInOrderBySortOrderAsc(
            Collection<String> rulesIds);
}
