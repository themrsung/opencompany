package com.coreintra.app.api.approval.support;

import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.ApprovalLineTemplateRepository;
import com.coreintra.approval.repository.ApprovalTemplateStepRepository;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import com.coreintra.attendance.entity.LeavePolicy;
import com.coreintra.attendance.repository.LeavePolicyRepository;
import com.coreintra.auth.service.SessionService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.EmployeeRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.RankRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import javax.servlet.http.Cookie;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One small company, seeded into the real schema, and the cookies to act as the
 * people in it.
 *
 * <h2>Why the whole company, and why the real tables</h2>
 *
 * <p>Almost everything the API tests are about is a question the org chart and
 * the 결재선 tables answer: who the 부장 of the drafter's unit is on a date,
 * whether two representatives are distinct people, whether a company is 각자대표
 * or 공동대표 on the day a document was submitted. Faking those ports would let
 * the API pass while the real adapters disagreed, which is the failure
 * integration tests exist to catch — so this writes
 * {@code approval_line_template}, {@code approval_template_step},
 * {@code company_representation} and {@code leave_policy} and lets the
 * production adapters read them back.
 *
 * <p>A {@link TestComponent} rather than a {@code @Component}: the application
 * scans {@code com.coreintra} and the test classes are on that classpath, so a
 * plain component here would be registered into every other test's context too.
 *
 * <h2>The cast</h2>
 *
 * <ul>
 *   <li>김민준 — drafts things.</li>
 *   <li>박부장 — the ordinary approver.</li>
 *   <li>이대표 and 최대표 — two 대표이사s, so 공동대표 has something to be joint
 *       about.</li>
 *   <li>정참조 — copied on documents and never asked to act.</li>
 *   <li>강마스터 — a master account, present to prove that being one does not
 *       help with 취업규칙.</li>
 * </ul>
 */
@TestComponent
public class ApiTestWorld {

    /** The document type the 결재선 template covers. */
    public static final String DOCUMENT_TYPE = "EXPENSE_CLAIM";

    /** Above this — inclusive — the template adds a 대표 step. */
    public static final BigDecimal REPRESENTATIVE_THRESHOLD = new BigDecimal("5000000");

    /** The leave policy the attendance tests draw from. */
    public static final String LEAVE_POLICY_CODE = "ANNUAL";

    private final DataSource dataSource;
    private final SessionService sessions;
    private final CompanyRepository companies;
    private final OrgUnitRepository orgUnits;
    private final RankRepository ranks;
    private final EmployeeRepository employees;
    private final UserAccountRepository accounts;
    private final PositionRepository positions;
    private final PermissionGrantRepository grants;
    private final ApprovalLineTemplateRepository templates;
    private final ApprovalTemplateStepRepository templateSteps;
    private final CompanyRepresentationRepository representations;
    private final LeavePolicyRepository leavePolicies;

    public String companyId;
    public String headOfficeId;
    public String financeUnitId;
    public String rankStaffId;
    public String rankBujangId;
    public String rankRepresentativeId;
    public String leavePolicyId;

    public String drafterEmployeeId;
    public String drafterAccountId;
    public String approverEmployeeId;
    public String approverAccountId;
    public String firstRepEmployeeId;
    public String firstRepAccountId;
    public String secondRepEmployeeId;
    public String secondRepAccountId;
    public String observerEmployeeId;
    public String observerAccountId;
    public String masterEmployeeId;
    public String masterAccountId;

    public ApiTestWorld(DataSource dataSource, SessionService sessions,
            CompanyRepository companies, OrgUnitRepository orgUnits, RankRepository ranks,
            EmployeeRepository employees, UserAccountRepository accounts,
            PositionRepository positions, PermissionGrantRepository grants,
            ApprovalLineTemplateRepository templates,
            ApprovalTemplateStepRepository templateSteps,
            CompanyRepresentationRepository representations,
            LeavePolicyRepository leavePolicies) {
        this.dataSource = dataSource;
        this.sessions = sessions;
        this.companies = companies;
        this.orgUnits = orgUnits;
        this.ranks = ranks;
        this.employees = employees;
        this.accounts = accounts;
        this.positions = positions;
        this.grants = grants;
        this.templates = templates;
        this.templateSteps = templateSteps;
        this.representations = representations;
        this.leavePolicies = leavePolicies;
    }

    /**
     * Wipes and re-seeds.
     *
     * <p>Deletion order is children before parents; a foreign key will otherwise
     * refuse, and the failure surfaces as an unrelated test error three classes
     * later.
     *
     * <p>The default representation is 각자대표. There is no fallback inside the
     * directory — a company with no arrangement on file is refused rather than
     * quietly treated as single-signature — so every company this seeds has one.
     */
    public void reset() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("delete from employment_rules_acknowledgement");
        jdbc.execute("delete from employment_rules_representative");
        jdbc.execute("delete from employment_rules_section");
        jdbc.execute("delete from employment_rules");
        jdbc.execute("delete from approval_action");
        jdbc.execute("delete from approval_step_approver");
        jdbc.execute("delete from approval_step");
        jdbc.execute("delete from approval_document");
        jdbc.execute("delete from approval_template_step");
        jdbc.execute("delete from approval_line_template");
        jdbc.execute("delete from company_representation");
        jdbc.execute("delete from attendance_record");
        jdbc.execute("delete from attendance_status_type");
        jdbc.execute("delete from leave_transaction");
        jdbc.execute("delete from leave_tenure_increment");
        jdbc.execute("delete from leave_policy");
        jdbc.execute("delete from idempotency_key");
        jdbc.execute("delete from auth_session");
        jdbc.execute("delete from permission_grant");
        jdbc.execute("delete from position_job_function");
        jdbc.execute("delete from position");
        jdbc.execute("delete from user_account");
        jdbc.execute("delete from employee");
        jdbc.execute("delete from org_unit");
        jdbc.execute("delete from rank");
        jdbc.execute("delete from company");

        companyId = id();
        companies.save(new Company(companyId, "acme", "에이스전자",
                Company.CompanyKind.HEAD_OFFICE));

        OrgUnit headOffice = new OrgUnit(id(), companyId, "hq", "본사");
        OrgUnit finance = new OrgUnit(id(), companyId, "finance", "회계팀");
        finance.attachTo(headOffice);
        orgUnits.save(headOffice);
        orgUnits.save(finance);
        headOfficeId = headOffice.id();
        financeUnitId = finance.id();

        Rank staff = new Rank(id(), companyId, "sawon", "사원", 10);
        Rank bujang = new Rank(id(), companyId, "bujang", "부장", 50);
        Rank representative = new Rank(id(), companyId, "daepyo", "대표이사", 90);
        // The 대표 step resolves from this flag rather than from the label, so a
        // client that calls the post something else still gets a working quorum.
        representative.setRepresentative(true);
        ranks.save(staff);
        ranks.save(bujang);
        ranks.save(representative);
        rankStaffId = staff.id();
        rankBujangId = bujang.id();
        rankRepresentativeId = representative.id();

        drafterEmployeeId = person("김민준", "minjun", financeUnitId, rankStaffId, false);
        drafterAccountId = lastAccountId;
        approverEmployeeId = person("박부장", "bakbujang", financeUnitId, rankBujangId, false);
        approverAccountId = lastAccountId;
        firstRepEmployeeId = person("이대표", "leedaepyo", headOfficeId, rankRepresentativeId,
                false);
        firstRepAccountId = lastAccountId;
        secondRepEmployeeId = person("최대표", "choedaepyo", headOfficeId, rankRepresentativeId,
                false);
        secondRepAccountId = lastAccountId;
        observerEmployeeId = person("정참조", "jeongchamjo", financeUnitId, rankStaffId, false);
        observerAccountId = lastAccountId;
        masterEmployeeId = person("강마스터", "master", headOfficeId, rankBujangId, true);
        masterAccountId = lastAccountId;

        // Company-wide reads and writes for everybody. These tests are not about
        // who holds which grant — the org permission suite covers that — so the
        // grants are deliberately generous, which makes any refusal that does
        // happen unambiguously a domain rule rather than a missing grant.
        for (String accountId : Immutables.listOf(drafterAccountId, approverAccountId,
                firstRepAccountId, secondRepAccountId, observerAccountId, masterAccountId)) {
            grant(accountId, "approval.document:read");
            grant(accountId, "approval.document:write");
            grant(accountId, "hr.rules:read");
            grant(accountId, "hr.rules:write");
            grant(accountId, "hr.attendance:read");
            grant(accountId, "hr.attendance:write");
            grant(accountId, "hr.attendance.status:write");
            grant(accountId, "hr.leave:read");
            grant(accountId, "hr.leave:write");
        }

        useRepresentation(RepresentationMode.several(2));
        seedLeavePolicy();
    }

    /**
     * Replaces the company's representation arrangement.
     *
     * <p>Effective from long before anything the tests date, and open-ended, so
     * that a document submitted on any date they choose is covered.
     */
    public void useRepresentation(RepresentationMode mode) {
        new JdbcTemplate(dataSource).update(
                "delete from company_representation where company_id = ?", companyId);
        representations.save(new CompanyRepresentationEntity(id(), companyId, mode,
                LocalDate.of(2000, 1, 1), null));
    }

    /**
     * The 결재선 the tests submit against: 기안 → 부장 결재 → 참조, with a 대표
     * step added above the threshold.
     *
     * <p>The 부장 and 참조 steps name accounts rather than ranks, so a failure is
     * about the approval rules rather than about rank resolution, which has its
     * own suite. The 대표 step is the exception and uses the representative role
     * expression, because resolving <em>both</em> 대표이사s from the rank flag is
     * exactly what makes the 공동대표 quorum test mean anything.
     */
    public void registerExpenseTemplate() {
        String templateId = id();
        templates.save(new ApprovalLineTemplateEntity(templateId, companyId, DOCUMENT_TYPE,
                null, "지출결의 기본 결재선"));

        templateSteps.save(new ApprovalTemplateStepEntity(id(), templateId, 1,
                ApprovalStepKind.APPROVE, RoleExpression.account(approverAccountId).toString(),
                false, null, null));
        templateSteps.save(new ApprovalTemplateStepEntity(id(), templateId, 3,
                ApprovalStepKind.CC, RoleExpression.account(observerAccountId).toString(),
                false, null, null));
        templateSteps.save(new ApprovalTemplateStepEntity(id(), templateId, 2,
                ApprovalStepKind.APPROVE, RoleExpression.representative().toString(),
                false, REPRESENTATIVE_THRESHOLD,
                "5,000,000원을 넘는 지출은 대표이사 결재가 필요합니다."));
    }

    /** A line whose only step is 대표자 결재 — what a 취업규칙 change travels through. */
    public void registerRepresentativeOnlyTemplate(String documentType) {
        String templateId = id();
        templates.save(new ApprovalLineTemplateEntity(templateId, companyId, documentType,
                null, "취업규칙 개정 결재선"));
        templateSteps.save(new ApprovalTemplateStepEntity(id(), templateId, 1,
                ApprovalStepKind.APPROVE, RoleExpression.representative().toString(),
                false, null, null));
    }

    /**
     * The Korean 연차 default, as a fresh install ships it.
     *
     * <p>Half-day units, because that is what makes the "a balance is a decimal
     * string" assertion a statement about something real rather than about a
     * whole number that happens to be rendered as text.
     */
    private void seedLeavePolicy() {
        LeavePolicy policy = new LeavePolicy(id(), companyId, LEAVE_POLICY_CODE,
                "연차유급휴가 (기본)");
        policy.rename("연차유급휴가 (기본)", "Annual paid leave (default)");
        policy.setMonthlyAccrualDays(new BigDecimal("1"));
        policy.setAnnualGrantDays(new BigDecimal("15"));
        policy.setAnnualGrantAfterYears(1);
        policy.setMaximumDays(new BigDecimal("25"));
        policy.setCarryOverExpiryMonths(12);
        policy.setMinimumBookableUnitDays(new BigDecimal("0.5"));
        policy.setBuiltIn(true);
        leavePolicies.save(policy);
        leavePolicyId = policy.id();
    }

    private String lastAccountId;

    private String person(String name, String username, String orgUnitId, String rankId,
            boolean master) {

        Employee employee = new Employee(id(), companyId, name);
        employees.save(employee);

        UserAccount account = new UserAccount(id(), username, name,
                UserAccount.AccountKind.USER);
        account.linkToEmployee(employee.id());
        account.setMaster(master);
        accounts.save(account);
        lastAccountId = account.id();

        Position position = new Position(id(), employee.id(), orgUnitId, rankId,
                LocalDate.of(2020, 1, 1));
        position.setPrimary(true);
        positions.save(position);
        return employee.id();
    }

    private void grant(String accountId, String key) {
        grants.save(new PermissionGrantRow(id(), GrantSource.USER_ACCOUNT, accountId,
                PermissionKey.parse(key), PermissionScope.COMPANY, true));
    }

    /**
     * A live access-token cookie for an account.
     *
     * <p>A real session rather than a stubbed principal, so the authentication
     * filter, the token hash and the session registry are all in the path. A
     * test that bypassed them would not be testing the API anybody uses.
     */
    public Cookie sessionFor(String accountId) {
        SessionService.IssuedSession issued =
                sessions.issue(accountId, "integration-test", "127.0.0.1");
        return new Cookie("ci_at", issued.accessToken());
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }
}
