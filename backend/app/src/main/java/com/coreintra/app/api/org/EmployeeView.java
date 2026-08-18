package com.coreintra.app.api.org;

import com.coreintra.core.org.Employee;
import java.util.function.Function;

/**
 * A person on the wire.
 *
 * <p>Deliberately thin. This is the record the org chart needs — who they are,
 * what number payroll knows them by, when they joined and whether they have
 * left. Anything more sensitive belongs to a resource with its own permission
 * rather than riding along on a list that {@code hr.employee:read} already
 * reaches.
 */
public class EmployeeView {

    public static final Function<Employee, EmployeeView> MAPPER = new Function<Employee, EmployeeView>() {
        @Override
        public EmployeeView apply(Employee employee) {
            return from(employee);
        }
    };

    /**
     * Employee number first, which is how every HR list in Korea is ordered.
     *
     * <p>The number is nullable for someone entered before payroll assigned one,
     * so it falls back to the Korean name; the id breaks the remaining ties, and
     * that is what makes the walk total rather than merely usually-total.
     */
    public static final Pages.Keys<Employee> KEYS = new Pages.Keys<Employee>() {
        @Override
        public String sortKey(Employee employee) {
            return employee.employeeNumber() == null ? "~" + employee.nameKo()
                    : employee.employeeNumber();
        }

        @Override
        public String id(Employee employee) {
            return employee.id();
        }
    };

    private final String id;
    private final String companyId;
    private final String employeeNumber;
    private final String nameKo;
    private final String nameEn;
    private final String email;
    private final String hiredOn;
    private final String terminatedOn;
    private final String createdAt;

    EmployeeView(String id, String companyId, String employeeNumber, String nameKo, String nameEn,
            String email, String hiredOn, String terminatedOn, String createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.employeeNumber = employeeNumber;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.email = email;
        this.hiredOn = hiredOn;
        this.terminatedOn = terminatedOn;
        this.createdAt = createdAt;
    }

    public static EmployeeView from(Employee employee) {
        return new EmployeeView(employee.id(), employee.companyId(), employee.employeeNumber(),
                employee.nameKo(), employee.nameEn(), employee.email(),
                employee.hiredOn() == null ? null : employee.hiredOn().toString(),
                employee.terminatedOn() == null ? null : employee.terminatedOn().toString(),
                employee.createdAt() == null ? null : employee.createdAt().toString());
    }

    public static String tagOf(Employee employee) {
        return OrgVersions.tag(employee.id(), employee.employeeNumber(), employee.nameKo(),
                employee.nameEn(), employee.email(), employee.hiredOn(), employee.terminatedOn());
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getEmail() {
        return email;
    }

    public String getHiredOn() {
        return hiredOn;
    }

    /** The last day worked. Null while employed; a leaver is never deleted. */
    public String getTerminatedOn() {
        return terminatedOn;
    }

    /** Real UTC. */
    public String getCreatedAt() {
        return createdAt;
    }
}
