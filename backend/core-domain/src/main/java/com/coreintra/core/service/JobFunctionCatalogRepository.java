package com.coreintra.core.service;

import com.coreintra.core.org.JobFunction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Every 직무 of a company, retired ones included.
 *
 * <p>The same reason as {@link OrgUnitCatalogRepository}: the code uniqueness
 * constraint {@code job_function_code_unique_per_company} counts retired rows,
 * so a service that only ever sees active rows will happily accept a code the
 * database is about to reject.
 */
public interface JobFunctionCatalogRepository extends Repository<JobFunction, String> {

    <S extends JobFunction> S save(S jobFunction);

    Optional<JobFunction> findById(String id);

    List<JobFunction> findByCompanyIdOrderByCodeAsc(String companyId);
}
