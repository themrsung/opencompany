package com.coreintra.approval.repository;

import com.coreintra.approval.entity.EmploymentRulesSectionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmploymentRulesSectionRepository
        extends JpaRepository<EmploymentRulesSectionEntity, EmploymentRulesSectionEntity.Key> {

    List<EmploymentRulesSectionEntity> findByRulesIdOrderBySortOrderAsc(String rulesId);

    /**
     * Sections of several versions at once.
     *
     * <p>Reading a company's history is a version list plus one query, not one
     * query per version: the 취업규칙 screen shows every version's sections side
     * by side, and the diff walks pairs of them.
     */
    List<EmploymentRulesSectionEntity> findByRulesIdInOrderBySortOrderAsc(
            Collection<String> rulesIds);
}
