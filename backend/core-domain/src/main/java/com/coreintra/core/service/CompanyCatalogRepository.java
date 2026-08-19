package com.coreintra.core.service;

import com.coreintra.core.org.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Companies, active and inactive, plus the two lookups a write needs.
 *
 * <p>{@code company_code_unique} is installation-wide and counts deactivated
 * rows, so the uniqueness check has to see them; and a re-parent has to walk the
 * ownership chain upwards, which is what {@link #findByParentCompanyId} exists
 * for.
 */
public interface CompanyCatalogRepository extends Repository<Company, String> {

    <S extends Company> S save(S company);

    Optional<Company> findById(String id);

    List<Company> findAllByOrderByCodeAsc();

    Optional<Company> findByCode(String code);

    List<Company> findByParentCompanyId(String parentCompanyId);
}
