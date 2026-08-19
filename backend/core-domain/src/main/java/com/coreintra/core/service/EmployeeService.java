package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.Position;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People, as employment facts only.
 *
 * <p>No salary, no bank account, no payslip - see {@link Employee} for why that
 * bound is deliberate. It is what keeps the temporary-master threat model
 * tractable.
 *
 * <h2>Why a list is filtered rather than refused</h2>
 *
 * <p>A permission check needs a target, and an employee's target carries the
 * unit they stand in, so a 팀장 with an {@code ORG_UNIT} grant sees their own
 * team and nobody else's. Refusing the whole list because one row is out of
 * reach would be an answer nobody can act on. A single read by id still throws,
 * because there the caller named the row and deserves to be told no.
 *
 * <h2>Leavers</h2>
 *
 * <p>A leaver is not a deletion. Last quarter's approvals were signed by people
 * who have since left, and hiding them makes those documents unreadable, so
 * termination is a date and the list can include them on request.
 */
@Service
public class EmployeeService {

    private final EmployeeCatalogRepository employees;
    private final PositionAssignmentRepository positions;
    private final PermissionEvaluator evaluator;

    public EmployeeService(EmployeeCatalogRepository employees, PositionAssignmentRepository positions,
            PermissionEvaluator evaluator) {
        if (employees == null) {
            throw new NullPointerException("employees");
        }
        if (positions == null) {
            throw new NullPointerException("positions");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.employees = employees;
        this.positions = positions;
        this.evaluator = evaluator;
    }

    /** Everyone in the company the caller may read, by name. Leavers only when asked for. */
    @Transactional(readOnly = true)
    public List<Employee> list(PermissionPrincipal caller, String companyId, boolean includeLeavers,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");

        List<Employee> visible = new ArrayList<Employee>();
        for (Employee employee : employees.findByCompanyIdOrderByNameKoAsc(company)) {
            if (!includeLeavers && !employee.isEmployedOn(businessDate)) {
                continue;
            }
            if (evaluator.check(caller, OrgPermissions.EMPLOYEE_READ, target(employee, businessDate)).isAllowed()) {
                visible.add(employee);
            }
        }
        return Immutables.copyOf(visible);
    }

    @Transactional(readOnly = true)
    public Employee read(PermissionPrincipal caller, String employeeId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee employee = require(employeeId);
        evaluator.check(caller, OrgPermissions.EMPLOYEE_READ, target(employee, businessDate)).orThrow();
        return employee;
    }

    /**
     * Adds a person.
     *
     * <p>A new hire has no position yet, so there is no unit to check against and
     * no unit-scoped grant can reach them. Creation is therefore checked at
     * company level: putting a person on the books is authority over the company,
     * and giving them a place in the chart is a separate act with its own check
     * (see {@link PositionService#assign}).
     */
    @Transactional
    public Employee create(PermissionPrincipal caller, String companyId, String employeeNumber, String nameKo,
            String nameEn, String email, LocalDate hiredOn, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        evaluator.check(caller, OrgPermissions.EMPLOYEE_CREATE,
                OrgTargets.company(company, businessDate, "employees of company " + company)).orThrow();

        String name = Arguments.required(nameKo, "nameKo");
        Employee employee = new Employee(UUID.randomUUID().toString(), company, name);
        employee.rename(name, Arguments.optional(nameEn));
        employee.setEmployeeNumber(uniqueNumber(company, employeeNumber, null));
        employee.setEmail(Arguments.optional(email));
        employee.setHiredOn(hiredOn);
        return employees.save(employee);
    }

    @Transactional
    public Employee update(PermissionPrincipal caller, String employeeId, String employeeNumber, String nameKo,
            String nameEn, String email, LocalDate hiredOn, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee employee = require(employeeId);
        evaluator.check(caller, OrgPermissions.EMPLOYEE_UPDATE, target(employee, businessDate)).orThrow();

        employee.rename(Arguments.required(nameKo, "nameKo"), Arguments.optional(nameEn));
        employee.setEmployeeNumber(uniqueNumber(employee.companyId(), employeeNumber, employee.id()));
        employee.setEmail(Arguments.optional(email));
        employee.setHiredOn(hiredOn);
        return employees.save(employee);
    }

    /**
     * Records a last day.
     *
     * <p>Positions are not closed here. A leaver whose position stays open on
     * paper is visible and fixable; a leaver whose positions were silently closed
     * has had last quarter's approval chain rewritten underneath them. Closing is
     * an explicit act on {@link PositionService}.
     */
    @Transactional
    public Employee terminate(PermissionPrincipal caller, String employeeId, LocalDate lastDay,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee employee = require(employeeId);
        evaluator.check(caller, OrgPermissions.EMPLOYEE_TERMINATE, target(employee, businessDate)).orThrow();

        if (lastDay == null) {
            throw new IllegalArgumentException("lastDay is required");
        }
        if (employee.hiredOn() != null && lastDay.isBefore(employee.hiredOn())) {
            throw new IllegalArgumentException("last day " + lastDay + " is before the hire date "
                    + employee.hiredOn());
        }
        employee.terminate(lastDay);
        return employees.save(employee);
    }

    /**
     * The permission target for one person, carrying the unit they stood in on the
     * date.
     *
     * <p>Package-visible because {@link PositionService} authorises against the
     * same shape, and two spellings of "where does this person sit" would be two
     * answers.
     */
    PermissionTarget target(Employee employee, LocalDate businessDate) {
        return OrgTargets.employee(employee.companyId(), unitOn(employee.id(), businessDate), employee.id(),
                businessDate, "employee " + employee.nameKo());
    }

    /** The unit a person stood in on a date, primary position first, or null if none. */
    String unitOn(String employeeId, LocalDate businessDate) {
        String fallback = null;
        for (Position position : positions.findByEmployeeIdOrderByEffectiveFromAsc(employeeId)) {
            if (!position.isActiveOn(businessDate)) {
                continue;
            }
            if (position.isPrimary()) {
                return position.orgUnitId();
            }
            if (fallback == null) {
                fallback = position.orgUnitId();
            }
        }
        return fallback;
    }

    Employee require(String employeeId) {
        Optional<Employee> found = employees.findById(Arguments.required(employeeId, "employeeId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("employee", employeeId);
        }
        return found.get();
    }

    /**
     * The client's own employee number, unique per company.
     *
     * <p>Optional: plenty of installations number people only in the payroll system
     * this one deliberately does not hold.
     */
    private String uniqueNumber(String companyId, String employeeNumber, String ownId) {
        String number = Arguments.optional(employeeNumber);
        if (number == null) {
            return null;
        }
        Optional<Employee> holder = employees.findByCompanyIdAndEmployeeNumber(companyId, number);
        if (holder.isPresent() && !holder.get().id().equals(ownId)) {
            throw new IllegalArgumentException("employee number is already in use in this company: " + number);
        }
        return number;
    }
}
