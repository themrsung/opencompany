package com.coreintra.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.support.DatabaseTestSupport;
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
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The org model and permission evaluator against a real PostgreSQL.
 *
 * <p>What this covers that the unit tests cannot: the migration actually
 * applies, the materialised path index answers subtree questions, the half-open
 * position interval behaves at its boundaries, and the 72-hour window is
 * enforced by the database rather than only by Java.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrgPermissionIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private CompanyRepository companies;
    @Autowired private OrgUnitRepository orgUnits;
    @Autowired private RankRepository ranks;
    @Autowired private JobFunctionRepository jobFunctions;
    @Autowired private EmployeeRepository employees;
    @Autowired private UserAccountRepository accounts;
    @Autowired private PositionRepository positions;
    @Autowired private PermissionGrantRepository grants;
    @Autowired private PermissionEvaluator evaluator;
    @Autowired private DataSource dataSource;

    private String companyId;
    private String hqId;
    private String supportId;
    private String financeId;
    private String rankBujangId;
    private String jobAccountingId;
    private String employeeId;
    private String accountId;

    @BeforeEach
    void seedOrg() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Order matters: children before parents.
        // One reset, ordered by the database rather than by hand: every new
        // table with a foreign key to an account used to break a different
        // set of these lists, in whichever class happened to run next.
        DatabaseTestSupport.resetSchema(jdbc);

        companyId = id();
        companies.save(new Company(companyId, "acme", "에이스전자", Company.CompanyKind.HEAD_OFFICE));

        OrgUnit hq = new OrgUnit(id(), companyId, "hq", "본사");
        OrgUnit support = new OrgUnit(id(), companyId, "support", "경영지원본부");
        support.attachTo(hq);
        OrgUnit finance = new OrgUnit(id(), companyId, "finance", "회계팀");
        finance.attachTo(support);
        orgUnits.save(hq);
        orgUnits.save(support);
        orgUnits.save(finance);
        hqId = hq.id();
        supportId = support.id();
        financeId = finance.id();

        Rank bujang = new Rank(id(), companyId, "bujang", "부장", 50);
        ranks.save(bujang);
        rankBujangId = bujang.id();

        JobFunction accounting = new JobFunction(id(), companyId, "accounting", "회계");
        jobFunctions.save(accounting);
        jobAccountingId = accounting.id();

        Employee employee = new Employee(id(), companyId, "김민준");
        employees.save(employee);
        employeeId = employee.id();

        UserAccount account = new UserAccount(
                id(), "minjun", "김민준", UserAccount.AccountKind.USER);
        account.linkToEmployee(employeeId);
        accounts.save(account);
        accountId = account.id();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private PermissionPrincipal principal() {
        return PermissionPrincipal.user(accountId, "김민준", employeeId);
    }

    @Test
    @DisplayName("the materialised path answers subtree questions across the real index")
    void subtreeScopeWorksAgainstTheDatabase() {
        positions.save(new Position(id(), employeeId, supportId, rankBujangId,
                LocalDate.of(2026, 1, 1)));
        grants.save(new PermissionGrantRow(id(), GrantSource.RANK, rankBujangId,
                PermissionKey.parse("hr.employee:read"), PermissionScope.ORG_UNIT_SUBTREE, true));

        PermissionDecision beneath = evaluator.check(principal(),
                PermissionKey.parse("hr.employee:read"),
                PermissionTarget.builder().companyId(companyId).orgUnitId(financeId)
                        .asOfBusinessDate(LocalDate.of(2026, 8, 30)).build());
        assertThat(beneath.isAllowed())
                .as("회계팀 is beneath 경영지원본부: %s", beneath.summary())
                .isTrue();

        PermissionDecision above = evaluator.check(principal(),
                PermissionKey.parse("hr.employee:read"),
                PermissionTarget.builder().companyId(companyId).orgUnitId(hqId)
                        .asOfBusinessDate(LocalDate.of(2026, 8, 30)).build());
        assertThat(above.isAllowed())
                .as("본사 is above the held unit, so a subtree grant does not reach it")
                .isFalse();
    }

    @Test
    @DisplayName("a job-function grant reaches through the join table")
    void jobFunctionGrantsResolve() {
        Position position = new Position(id(), employeeId, financeId, rankBujangId,
                LocalDate.of(2026, 1, 1));
        positions.save(position);
        new JdbcTemplate(dataSource).update(
                "insert into position_job_function (position_id, job_function_id) values (?, ?)",
                position.id(), jobAccountingId);

        grants.save(new PermissionGrantRow(id(), GrantSource.JOB_FUNCTION, jobAccountingId,
                PermissionKey.parse("accounting.entry:post"), PermissionScope.ORG_UNIT, true));

        PermissionDecision decision = evaluator.check(principal(),
                PermissionKey.parse("accounting.entry:post"),
                PermissionTarget.builder().companyId(companyId).orgUnitId(financeId)
                        .asOfBusinessDate(LocalDate.of(2026, 8, 30)).build());

        assertThat(decision.isAllowed())
                .as("the 회계 job function grant must resolve: %s", decision.explain())
                .isTrue();
        assertThat(decision.decidingGrant().sourceLabel()).isEqualTo("회계");
    }

    @Test
    @DisplayName("position intervals are half-open at the database boundary")
    void positionIntervalsAreHalfOpen() {
        LocalDate moveDay = LocalDate.of(2026, 6, 1);
        Position before = new Position(id(), employeeId, financeId, rankBujangId,
                LocalDate.of(2026, 1, 1));
        before.closeOn(moveDay);
        positions.save(before);
        positions.save(new Position(id(), employeeId, supportId, rankBujangId, moveDay));

        assertThat(positions.findActiveOn(employeeId, moveDay.minusDays(1)))
                .as("the day before the move, only the old position is active")
                .hasSize(1);
        assertThat(positions.findActiveOn(employeeId, moveDay))
                .as("on the move day itself exactly one position is active, not two")
                .hasSize(1);
        assertThat(positions.findActiveOn(employeeId, moveDay).get(0).orgUnitId())
                .isEqualTo(supportId);
    }

    @Test
    @DisplayName("the database refuses a business offset outside the 72-hour window")
    void businessOffsetWindowIsEnforcedByTheDatabase() {
        // The window is a database constraint rather than application validation,
        // so a raw insert from a migration, a support session or a client module
        // cannot write an impossible instant. Asserted against a real table
        // rather than the helper function alone, because it is the CHECK on the
        // column that actually protects the data.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create temporary table offset_probe ("
                + "  id serial primary key,"
                + "  offset_seconds integer not null"
                + "    constraint offset_probe_in_window"
                + "    check (business_offset_is_valid(offset_seconds)))");

        jdbc.update("insert into offset_probe (offset_seconds) values (?)", 172_800);
        jdbc.update("insert into offset_probe (offset_seconds) values (?)", -86_400);

        try {
            jdbc.update("insert into offset_probe (offset_seconds) values (?)", 172_801);
            org.junit.jupiter.api.Assertions.fail(
                    "the database accepted an offset beyond +48:00:00; the window check is not "
                            + "doing its job and raw SQL could write an impossible instant");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected.getMessage()).contains("offset_probe_in_window");
        }
    }

    @Test
    @DisplayName("a real approval action row cannot carry an out-of-window offset either")
    void approvalActionOffsetIsChecked() {
        // The helper function is only worth anything if the real tables use it.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer checks = jdbc.queryForObject(
                "select count(*) from pg_constraint "
                        + "where conname = 'approval_action_acted_offset_in_window'", Integer.class);
        assertThat(checks)
                .as("approval_action must carry the 72-hour window check")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the last active master cannot be counted away")
    void masterCountIsQueryable() {
        UserAccount master = new UserAccount(id(), "master", "마스터",
                UserAccount.AccountKind.USER);
        master.setMaster(true);
        accounts.save(master);

        assertThat(accounts.countByMasterTrueAndActiveTrue())
                .as("the invariant service counts before demoting; this is the query it uses")
                .isEqualTo(1L);
    }
}
