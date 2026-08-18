package com.coreintra.core.org.repository;

import com.coreintra.core.org.Employee;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, String> {
    List<Employee> findByCompanyId(String companyId);
}
