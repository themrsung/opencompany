package com.coreintra.app.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.accounting.persistence.BookRow;
import com.coreintra.accounting.service.BookService;
import com.coreintra.accounting.service.LedgerReportService;
import com.coreintra.approval.service.ApprovalInbox;
import com.coreintra.approval.service.ApprovalInboxService;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.service.AttendanceRecordService;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.CompanyService;
import com.coreintra.core.service.EmployeeService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The seed, run against a real PostgreSQL, twice.
 *
 * <p>What is worth asserting about a seed is not that it inserted rows - it is that the rows it
 * inserted mean what the demo says they mean. So the checks here are the four claims the seed
 * makes that would embarrass us if they were wrong: the demo account has something in its inbox,
 * the ledger balances, the night shift is filed under the day it began rather than the day it
 * ended, and running the command a second time changes nothing.
 *
 * <p>The seed is driven directly rather than through the {@code ApplicationRunner}, which is
 * disabled here, because the second run has to happen inside the same test.
 */
@SpringBootTest(properties = "coreintra.seed.run-on-start=false")
@ActiveProfiles({"test", DemoSeedRunner.SEED_PROFILE})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeedIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private DemoSeed seed;
    @Autowired private DataSource dataSource;
    @Autowired private UserAccountRepository accounts;
    @Autowired private CompanyService companies;
    @Autowired private EmployeeService employees;
    @Autowired private ApprovalInboxService inboxes;
    @Autowired private AttendanceRecordService attendance;
    @Autowired private BookService books;
    @Autowired private LedgerReportService reports;
    @Autowired private AuthenticationService authentication;

    private static SeedSummary firstRun;
    private static SeedSummary secondRun;
    private static List<Long> countsAfterFirstRun;
    private static List<Long> countsAfterSecondRun;

    private JdbcTemplate jdbc;

    @BeforeEach
    void seedOnce() {
        jdbc = new JdbcTemplate(dataSource);
        if (firstRun != null) {
            return;
        }
        emptyTheInstallation();
        refusesAgainstSomebodyElsesData();

        firstRun = seed.run();
        countsAfterFirstRun = rowCounts();
        secondRun = seed.run();
        countsAfterSecondRun = rowCounts();
    }

    @Test
    @Order(1)
    @DisplayName("the seed builds the whole demo company in one command")
    void completes() {
        assertThat(firstRun.isAlreadySeeded()).isFalse();
        assertThat(firstRun.employeeCount()).isEqualTo(DemoPeople.total());
        assertThat(firstRun.employeeCount()).isBetween(38, 45);
        assertThat(firstRun.accountCount())
                .isEqualTo(DemoCompany.ACCOUNT_EMPLOYEE_NUMBERS.length);
        assertThat(firstRun.documentCount()).isGreaterThanOrEqualTo(6);
        assertThat(firstRun.attendanceRecordCount()).isGreaterThan(200);
        // The console report is the only thing an operator sees; it should name the account they
        // are about to sign in as and the arrangement the company runs under.
        assertThat(firstRun.describe()).contains(DemoCompany.DEMO_USERNAME).contains("공동대표");

        List<Company> seeded = companies.list(operator(), DemoCompany.TODAY);
        assertThat(seeded).hasSize(2);
        Company subsidiary = byCode(seeded, DemoCompany.SUBSIDIARY_CODE);
        assertThat(subsidiary.parentCompanyId())
                .isEqualTo(byCode(seeded, DemoCompany.HQ_CODE).id());
        assertThat(subsidiary.kind()).isEqualTo(Company.CompanyKind.SUBSIDIARY);
    }

    @Test
    @Order(2)
    @DisplayName("running the seed a second time recognises its own work and changes nothing")
    void runningTwiceIsSafe() {
        assertThat(secondRun.isAlreadySeeded()).isTrue();
        assertThat(countsAfterSecondRun).isEqualTo(countsAfterFirstRun);
        assertThat(secondRun.employeeCount()).isEqualTo(firstRun.employeeCount());
        assertThat(secondRun.demoAccountId()).isEqualTo(firstRun.demoAccountId());
    }

    @Test
    @Order(3)
    @DisplayName("the account the operator is told to sign in as has a populated 결재함")
    void demoInboxIsPopulated() {
        UserAccount demo = accounts.findByUsername(DemoCompany.DEMO_USERNAME).orElse(null);
        assertThat(demo).isNotNull();

        PermissionPrincipal asDemo = PermissionPrincipal.user(demo.id(), demo.displayName(),
                demo.employeeId());
        ApprovalInbox inbox = inboxes.load(asDemo, firstRun.hqCompanyId(), demo.id());

        assertThat(inbox.isEmpty()).isFalse();
        // Something to sign, something of his own in flight: an inbox with only one of the two
        // demonstrates half of the screen.
        assertThat(inbox.awaitingMe()).isNotEmpty();
        assertThat(inbox.draftedByMe()).isNotEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("공동대표 approval stops at one signature of the two the quorum requires")
    void jointRepresentationIsNotSatisfiedByOneApprover() {
        // The second representative is still holding it: one signature of two is a real state,
        // and it is the state a 각자대표 company cannot produce.
        UserAccount secondRepresentative = accounts.findByUsername("lee.sumin").orElse(null);
        assertThat(secondRepresentative).isNotNull();

        ApprovalInbox inbox = inboxes.load(
                PermissionPrincipal.user(secondRepresentative.id(),
                        secondRepresentative.displayName(), secondRepresentative.employeeId()),
                firstRun.hqCompanyId(), secondRepresentative.id());
        assertThat(inbox.awaitingMe()).isNotEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("the ledger balances, to the last decimal place nobody displays")
    void ledgerBalances() {
        assertThat(firstRun.isLedgerSkipped()).isFalse();
        assertThat(firstRun.isLedgerBalanced()).isTrue();

        BookRow book = books.book(operator(), firstRun.bookId(), DemoCompany.TODAY);
        assertThat(book.name()).isEqualTo(DemoCompany.BOOK_NAME);
        assertThat(reports.trialBalance(operator(), book.id(), DemoCompany.TODAY).isBalanced())
                .isTrue();

        // The accrual is stored as calculated. A seed that rounded on write would leave nothing
        // for the full-precision toggle to reveal.
        Long fractional = jdbc.queryForObject(
                "select count(*) from journal_posting where amount <> trunc(amount)", Long.class);
        assertThat(fractional).isGreaterThan(0L);

        Long foreign = jdbc.queryForObject(
                "select count(*) from journal_posting where currency_code = 'USD' "
                        + "and base_amount is not null", Long.class);
        assertThat(foreign).isGreaterThan(0L);
    }

    @Test
    @Order(6)
    @DisplayName("the shift that ends at 03:00 is stored on the business day it began")
    void midnightCrossingShiftKeepsItsBusinessDay() {
        Employee nightWorker = employee(DemoCompany.NIGHT_SHIFT_EMPLOYEE_NUMBER);
        List<AttendanceRecord> day = attendance.dayOf(operator(), firstRun.hqCompanyId(),
                nightWorker.id(), DemoCompany.NIGHT_SHIFT_DATE);

        AttendanceRecord crossing = null;
        for (AttendanceRecord record : day) {
            BusinessInstant endedAt = record.interval().endedAt();
            if (endedAt != null && endedAt.offsetSeconds() > 86_400) {
                crossing = record;
            }
        }

        assertThat(crossing)
                .as("a shift ending after 24:00 on %s", DemoCompany.NIGHT_SHIFT_DATE)
                .isNotNull();
        assertThat(crossing.businessDate()).isEqualTo(DemoCompany.NIGHT_SHIFT_DATE);
        assertThat(crossing.interval().endedAt().offsetSeconds()).isGreaterThan(86_400);
        assertThat(crossing.interval().endedAt().businessDate())
                .isEqualTo(DemoCompany.NIGHT_SHIFT_DATE);
        // The wall clock says the next morning; the business day does not.
        assertThat(crossing.interval().endedAt().absoluteDateTime().toLocalDate())
                .isEqualTo(DemoCompany.NIGHT_SHIFT_DATE.plusDays(1));
        assertThat(firstRun.nightShiftOffsetSeconds()).isGreaterThan(86_400);
    }

    @Test
    @Order(7)
    @DisplayName("the demo account can actually sign in with a printed recovery code")
    void demoCredentialsWork() {
        // The point of printing credentials at all. Enrolment is completed through the real
        // authentication service during the seed, so this is a genuine sign-in, and it consumes
        // one of the one-time codes exactly as it would for a person.
        List<String> codes = firstRun.recoveryCodesFor(DemoCompany.DEMO_USERNAME);
        assertThat(codes).isNotEmpty();
        assertThat(firstRun.credentials()).hasSize(DemoCompany.SIGN_IN_AS.length);
        assertThat(firstRun.credentials().get(0).otpauthUri()).startsWith("otpauth://");
        UserAccount signedIn = authentication.authenticateWithRecoveryCode(
                DemoCompany.DEMO_USERNAME, codes.get(0), "127.0.0.1");
        assertThat(signedIn.username()).isEqualTo(DemoCompany.DEMO_USERNAME);
    }

    @Test
    @Order(8)
    @DisplayName("the seed refuses to run outside the seed profile")
    void refusesOutsideTheSeedProfile() {
        MockEnvironment somethingElse = new MockEnvironment();
        somethingElse.setActiveProfiles("prod");
        DemoSeedRunner runner = new DemoSeedRunner(seed, somethingElse, true);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(SeedRefusedException.class)
                .hasMessageContaining("seed");
    }

    /**
     * The demo seed must be impossible to point at a client's box. Run before the demo exists,
     * against a database holding one row that is not ours.
     */
    private void refusesAgainstSomebodyElsesData() {
        jdbc.update("insert into company (id, code, name_ko, kind) values (?, ?, ?, ?)",
                UUID.randomUUID().toString(), "REAL-CO", "실제 고객사", "HEAD_OFFICE");
        try {
            assertThatThrownBy(() -> seed.run()).isInstanceOf(SeedRefusedException.class);
        } finally {
            emptyTheInstallation();
        }
    }

    private PermissionPrincipal operator() {
        UserAccount account = accounts.findByUsername(DemoCompany.OPERATOR_USERNAME).orElse(null);
        assertThat(account).isNotNull();
        return PermissionPrincipal.master(account.id(), account.displayName(), null);
    }

    private Employee employee(String employeeNumber) {
        for (Employee employee : employees.list(operator(), firstRun.hqCompanyId(), false,
                DemoCompany.TODAY)) {
            if (employeeNumber.equals(employee.employeeNumber())) {
                return employee;
            }
        }
        throw new AssertionError("사번 " + employeeNumber + " 을(를) 찾지 못하였습니다");
    }

    private static Company byCode(List<Company> all, String code) {
        for (Company company : all) {
            if (code.equals(company.code())) {
                return company;
            }
        }
        throw new AssertionError("회사 코드 " + code + " 을(를) 찾지 못하였습니다");
    }

    /** Row counts across every table the seed writes to, for the "twice changes nothing" check. */
    private List<Long> rowCounts() {
        String[] tables = {"company", "org_unit", "rank", "job_function", "employee", "position",
            "user_account", "permission_grant", "company_representation", "approval_line_template",
            "approval_template_step", "approval_document", "approval_step",
            "approval_step_approver", "approval_action", "attendance_record",
            "attendance_status_type", "book", "account", "journal_entry", "journal_posting"};
        List<Long> counts = new ArrayList<Long>(tables.length);
        for (String table : tables) {
            counts.add(jdbc.queryForObject("select count(*) from " + table, Long.class));
        }
        return counts;
    }

    /**
     * Empties every table Flyway created, leaving the schema in place. Testcontainers reuses one
     * database across the suite, so what another test class left behind is exactly the "somebody
     * else's data" the seed is supposed to refuse.
     */
    private void emptyTheInstallation() {
        // Deleted rather than truncated: audit_log is append-only at the database level and
        // refuses TRUNCATE by trigger, and TRUNCATE ... CASCADE would drag it in through its
        // foreign keys. Deleting in retried passes rather than in a hand-written order means
        // this keeps working when somebody adds a table.
        List<String> remaining = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public' "
                        + "and tablename not in ('flyway_schema_history', 'audit_log')",
                String.class);
        for (int pass = 0; pass < 6 && !remaining.isEmpty(); pass++) {
            List<String> blocked = new ArrayList<String>();
            for (String table : remaining) {
                try {
                    jdbc.execute("delete from " + table);
                } catch (DataAccessException stillHasChildren) {
                    blocked.add(table);
                }
            }
            remaining = blocked;
        }
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("could not empty " + remaining
                    + " before seeding; the demo seed needs an empty installation");
        }
    }
}
