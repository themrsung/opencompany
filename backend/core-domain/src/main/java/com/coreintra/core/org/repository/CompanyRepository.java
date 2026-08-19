package com.coreintra.core.org.repository;

import com.coreintra.core.org.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, String> {
}
