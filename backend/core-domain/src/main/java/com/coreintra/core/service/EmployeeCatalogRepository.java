package com.coreintra.core.service;

import com.coreintra.core.org.Employee;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Employees of one company, leavers included.
 *
 * <p>Leavers are part of the answer, not noise: last quarter's approvals were
 * signed by people who have since left, and a list that hides them makes those
 * documents unreadable. Filtering to who is employed on a given date is the
 * caller's decision, taken against {@link Employee#isEmployedOn}.
 *
 * <p>{@code employee_number_unique_per_company} counts leavers too, which is why
 * the number lookup exists here rather than being derived from a list of the
 * currently employed.
 */
public interface EmployeeCatalogRepository extends Repository<Employee, String> {

    <S extends Employee> S save(S employee);

    Optional<Employee> findById(String id);

    List<Employee> findByCompanyIdOrderByNameKoAsc(String companyId);

    Optional<Employee> findByCompanyIdAndEmployeeNumber(String companyId, String employeeNumber);
}
