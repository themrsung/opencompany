package com.coreintra.app.api.org;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.app.api.security.SessionCookies;
import com.coreintra.auth.service.SessionService;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.EmployeeRepository;
import com.coreintra.core.org.repository.JobFunctionRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.RankRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionScope;
import java.time.LocalDate;
import java.util.UUID;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds an organisation for the API tests, and hands out credentials for it.
 *
 * <p>Shared rather than copied into each test because the interesting part of
 * these tests is the HTTP behaviour, and twenty lines of 회사 setup at the top of
 * every one of them buries it. The fixture writes through the repositories so
 * the rows are the same rows production makes — a test that inserted its own SQL
 * would stop noticing when an entity and its table disagree, which is the thing
 * {@code ddl-auto: validate} exists to catch.
 *
 * <p>Credentials come from {@link SessionService#issue} rather than from the
 * sign-in endpoint: enrolling TOTP and generating a valid code for every test
 * would test the authentication path over and over, and it is already covered
 * by {@code AuthenticationIntegrationTest}. What these tests need is a caller
 * the filter resolves, which is exactly what an issued access token is.
 */
public class OrgApiFixture {

    private final DataSource dataSource;
    private final CompanyRepository companies;
    private final OrgUnitRepository orgUnits;
    private final RankRepository ranks;
    private final JobFunctionRepository jobFunctions;
    private final EmployeeRepository employees;
    private final UserAccountRepository accounts;
    private final PositionRepository positions;
    private final PermissionGrantRepository grants;
    private final SessionService sessions;

    public OrgApiFixture(DataSource dataSource, CompanyRepository companies,
            OrgUnitRepository orgUnits, RankRepository ranks, JobFunctionRepository jobFunctions,
            EmployeeRepository employees, UserAccountRepository accounts,
            PositionRepository positions, PermissionGrantRepository grants,
            SessionService sessions) {
        this.dataSource = dataSource;
        this.companies = companies;
        this.orgUnits = orgUnits;
        this.ranks = ranks;
        this.jobFunctions = jobFunctions;
        this.employees = employees;
        this.accounts = accounts;
        this.positions = positions;
        this.grants = grants;
        this.sessions = sessions;
    }

    /** Empties the tables these tests touch, children before parents. */
    public void reset() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // One reset, ordered by the database rather than by hand: every new
        // table with a foreign key to an account used to break a different
        // set of these lists, in whichever class happened to run next.
        DatabaseTestSupport.resetSchema(jdbc);
    }

    public String company(String code, String nameKo) {
        Company company = new Company(id(), code, nameKo, Company.CompanyKind.HEAD_OFFICE);
        companies.save(company);
        return company.id();
    }

    public String unit(String companyId, String code, String nameKo, String parentUnitId) {
        OrgUnit unit = new OrgUnit(id(), companyId, code, nameKo);
        if (parentUnitId != null) {
            unit.attachTo(orgUnits.findById(parentUnitId).get());
        }
        orgUnits.save(unit);
        return unit.id();
    }

    public String rank(String companyId, String code, String labelKo, int seniority) {
        Rank rank = new Rank(id(), companyId, code, labelKo, seniority);
        ranks.save(rank);
        return rank.id();
    }

    public String jobFunction(String companyId, String code, String labelKo) {
        JobFunction jobFunction = new JobFunction(id(), companyId, code, labelKo);
        jobFunctions.save(jobFunction);
        return jobFunction.id();
    }

    public String employee(String companyId, String employeeNumber, String nameKo) {
        Employee employee = new Employee(id(), companyId, nameKo);
        employee.setEmployeeNumber(employeeNumber);
        employees.save(employee);
        return employee.id();
    }

    public String account(String username, String displayName, String employeeId, boolean master) {
        UserAccount account =
                new UserAccount(id(), username, displayName, UserAccount.AccountKind.USER);
        if (employeeId != null) {
            account.linkToEmployee(employeeId);
        }
        account.setMaster(master);
        accounts.save(account);
        return account.id();
    }

    public String serviceAccount(String username, String displayName) {
        UserAccount account = new UserAccount(id(), username, displayName,
                UserAccount.AccountKind.SERVICE_ACCOUNT);
        accounts.save(account);
        return account.id();
    }

    public String position(String employeeId, String orgUnitId, String rankId, LocalDate from) {
        Position position = new Position(id(), employeeId, orgUnitId, rankId, from);
        positions.save(position);
        return position.id();
    }

    /** Attaches a grant directly, the way an administrator's screen would. */
    public String grant(GrantSource source, String sourceId, String permission,
            PermissionScope scope, boolean allow) {
        PermissionGrantRow row = new PermissionGrantRow(id(), source, sourceId,
                PermissionKey.parse(permission), scope, allow);
        grants.save(row);
        return row.id();
    }

    /** Everything an account needs to be allowed to administer accounts and grants. */
    public void grantAccountAdministration(String accountId) {
        grant(GrantSource.USER_ACCOUNT, accountId, "admin.account:read", PermissionScope.ALL, true);
        grant(GrantSource.USER_ACCOUNT, accountId, "admin.account:update", PermissionScope.ALL, true);
        grant(GrantSource.USER_ACCOUNT, accountId, "admin.master:update", PermissionScope.ALL, true);
    }

    /**
     * A browser-equivalent credential: the access token in its HttpOnly cookie.
     *
     * <p>A cookie and not an {@code Authorization} header, because that is the
     * only shape the filter accepts for a session — a bearer token that is not
     * an API key is refused rather than guessed at, so that sending a bogus
     * header cannot fall through and be authenticated as whoever the browser
     * session belongs to. A test that used a header would be exercising a path
     * production does not have.
     */
    public Cookie sessionCookie(String accountId) {
        return new Cookie(SessionCookies.ACCESS_COOKIE,
                sessions.issue(accountId, "integration-test", "127.0.0.1").accessToken());
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }
}
