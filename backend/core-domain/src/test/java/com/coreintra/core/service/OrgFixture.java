package com.coreintra.core.service;

import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.time.LocalDate;

/**
 * One company, wired the way a running installation is, with every service in
 * this package pointed at the same in-memory store.
 *
 * <pre>
 *   acme (에이컴)
 *     └── hq (본부)
 *           ├── finance (재경팀)
 *           └── sales   (영업팀)
 * </pre>
 *
 * <p>The fixture writes rows straight into the store rather than through the
 * services, so that a test about {@code move} is not also a test about {@code
 * create}. The one thing it never does is seed a grant through
 * {@link PermissionGrantService}: that service is the escalation gate, and a
 * fixture that went through it could only ever set up worlds the gate already
 * allows.
 */
final class OrgFixture {

    static final String COMPANY = "acme";
    static final LocalDate TODAY = LocalDate.of(2026, 8, 30);
    static final LocalDate LAST_MARCH = LocalDate.of(2026, 3, 15);
    static final LocalDate JUNE = LocalDate.of(2026, 6, 1);
    static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);

    final InMemoryOrgBook book = new InMemoryOrgBook();
    final PermissionEvaluator evaluator = new DefaultPermissionEvaluator(book, book);

    final CompanyService companies = new CompanyService(book.companies(), evaluator);
    final OrgUnitService units = new OrgUnitService(book.units(), book.positions(), evaluator);
    final RankService ranks = new RankService(book.ranks(), book.positions(), evaluator);
    final JobFunctionService jobFunctions = new JobFunctionService(book.jobFunctions(), book.positions(), evaluator);
    final EmployeeService employees = new EmployeeService(book.employees(), book.positions(), evaluator);
    final PositionService positions = new PositionService(book.positions(), book.units(), book.ranks(),
            book.jobFunctions(), employees, evaluator);
    final PermissionGrantService grants = new PermissionGrantService(book.grants(), book.ranks(),
            book.jobFunctions(), book.units(), book.accounts(), employees, evaluator);

    private int sequence;

    OrgFixture() {
        book.companies().save(new Company(COMPANY, "ACME", "에이컴", Company.CompanyKind.HEAD_OFFICE));
        unit("hq", "본부", null);
        unit("finance", "재경팀", "hq");
        unit("sales", "영업팀", "hq");
    }

    /** A unit, attached to a parent by id. Root when the parent is null. */
    OrgUnit unit(String id, String nameKo, String parentId) {
        OrgUnit unit = new OrgUnit(id, COMPANY, id, nameKo);
        if (parentId != null) {
            unit.attachTo(book.units().findById(parentId).get());
        }
        return book.units().save(unit);
    }

    /** A rung. Higher seniority is more senior; gaps are intentional. */
    Rank rank(String id, String labelKo, int seniority) {
        return book.ranks().save(new Rank(id, COMPANY, id, labelKo, seniority));
    }

    JobFunction jobFunction(String id, String labelKo) {
        return book.jobFunctions().save(new JobFunction(id, COMPANY, id, labelKo));
    }

    Employee employee(String id, String nameKo) {
        return book.employees().save(new Employee(id, COMPANY, nameKo));
    }

    /** A person with an account, which is what a {@link PermissionPrincipal} needs. */
    PermissionPrincipal person(String employeeId, String nameKo) {
        employee(employeeId, nameKo);
        UserAccount account = new UserAccount("acc-" + employeeId, employeeId, nameKo,
                UserAccount.AccountKind.USER);
        account.linkToEmployee(employeeId);
        book.account(account);
        return PermissionPrincipal.user(account.id(), nameKo, employeeId);
    }

    /** A position written straight in, open-ended from {@code from}. */
    Position position(String employeeId, String unitId, String rankId, LocalDate from) {
        Position position = new Position("pos-" + (++sequence), employeeId, unitId, rankId, from);
        return book.positions().save(position);
    }

    /** A position that has already ended. */
    Position position(String employeeId, String unitId, String rankId, LocalDate from, LocalDate to) {
        Position position = position(employeeId, unitId, rankId, from);
        position.closeOn(to);
        return book.positions().save(position);
    }

    void link(Position position, JobFunction jobFunction) {
        book.positions().linkJobFunction(position.id(), jobFunction.id());
    }

    PermissionGrantRow grant(GrantSource source, String sourceId, String permission, PermissionScope scope) {
        return seed(source, sourceId, permission, scope, true);
    }

    PermissionGrantRow deny(GrantSource source, String sourceId, String permission, PermissionScope scope) {
        return seed(source, sourceId, permission, scope, false);
    }

    private PermissionGrantRow seed(GrantSource source, String sourceId, String permission, PermissionScope scope,
            boolean allow) {
        PermissionGrantRow row = new PermissionGrantRow("grant-" + (++sequence), source, sourceId,
                PermissionKey.parse(permission), scope, allow);
        row.setGrantedBy("acc-installer");
        row.setReason("fixture");
        book.seedGrant(row);
        return row;
    }

    /**
     * An administrator who may run the org services company-wide.
     *
     * <p>A real employee with a real position, not a service account: a
     * {@code COMPANY}-scoped grant resolves against the org state, and an account
     * with no position stands in no company, so the grant would reach nothing.
     */
    PermissionPrincipal administrator() {
        rank("대표", "대표", 100);
        PermissionPrincipal admin = person("emp-admin", "관리자");
        position("emp-admin", "hq", "대표", JANUARY);
        for (String permission : new String[] {"hr.orgUnit:create", "hr.orgUnit:update", "hr.orgUnit:move",
                "hr.orgUnit:retire", "hr.orgUnit:read", "hr.rank:create", "hr.rank:update", "hr.rank:reorder",
                "hr.rank:retire", "hr.rank:read", "hr.jobFunction:create", "hr.jobFunction:update",
                "hr.jobFunction:retire", "hr.jobFunction:read", "hr.employee:create", "hr.employee:update",
                "hr.employee:terminate", "hr.employee:read", "hr.position:assign", "hr.position:close",
                "hr.position:read", "company.settings:read", "company.settings:update",
                "admin.permission:read", "admin.permission:grant", "admin.permission:revoke"}) {
            grant(GrantSource.USER_ACCOUNT, admin.accountId(), permission, PermissionScope.COMPANY);
        }
        // Incorporating a legal entity is checked installation-wide, so a
        // company-scoped grant of it would reach nothing - which is the point.
        grant(GrantSource.USER_ACCOUNT, admin.accountId(), "admin.company:create", PermissionScope.ALL);
        return admin;
    }
}
