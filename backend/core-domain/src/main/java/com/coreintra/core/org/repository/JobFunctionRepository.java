package com.coreintra.core.org.repository;

import com.coreintra.core.org.JobFunction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobFunctionRepository extends JpaRepository<JobFunction, String> {
    List<JobFunction> findByCompanyIdAndActiveTrue(String companyId);
}
