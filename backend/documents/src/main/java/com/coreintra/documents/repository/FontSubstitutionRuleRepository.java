package com.coreintra.documents.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coreintra.documents.entity.FontSubstitutionRuleEntity;

/** The client-editable fallback chains, in the order they are consulted. */
public interface FontSubstitutionRuleRepository
        extends JpaRepository<FontSubstitutionRuleEntity, String> {

    /** Ordered: the chain is a list, and a set would lose the client's preference. */
    List<FontSubstitutionRuleEntity> findByCompanyIdOrderByScopeAscScopeKeyAscPositionAsc(
            String companyId);

    List<FontSubstitutionRuleEntity> findByCompanyIdAndScopeAndScopeKeyOrderByPositionAsc(
            String companyId, FontSubstitutionRuleEntity.Scope scope, String scopeKey);
}
