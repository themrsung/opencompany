package com.coreintra.app.seed;

import com.coreintra.approval.service.ApprovalInbox;
import com.coreintra.approval.service.ApprovalInboxService;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.CompanyService;
import com.coreintra.core.service.EmployeeService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the demo installation, or says why it will not.
 *
 * <h2>Through the services, always</h2>
 *
 * <p>Every company, unit, rank, employee, position, grant, attendance record, approval and
 * journal entry below is created by calling the same service the REST layer calls, as a real
 * {@link PermissionPrincipal} with real grants. That is deliberate and it is the whole value of
 * this class: a seed that wrote rows would keep "working" for years after the rules changed
 * underneath it, and the demo it produced would show an installation no user could have
 * created. This one fails the day a service starts disagreeing with it.
 *
 * <p>The four exceptions - accounts, the first grants, the representation mode and the approval
 * line templates - have no service to call, and they are confined to {@link SeedBootstrap} with
 * an explanation of what is missing.
 *
 * <h2>Running twice</h2>
 *
 * <p>The seed detects its own operator account and stops, reporting what is already there rather
 * than making a second copy of it. Everything else runs inside one transaction, so a failure
 * half way through leaves an empty database and a non-zero exit rather than a company with
 * eleven employees and no approval lines, which looks fine until somebody clicks something.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
public class DemoSeed {

    private final SeedBootstrap bootstrap;
    private final OrgSeed org;
    private final AttendanceSeed attendance;
    private final ApprovalSeed approvals;
    private final LedgerSeed ledger;
    private final CompanyService companies;
    private final EmployeeService employees;
    private final ApprovalInboxService inbox;

    DemoSeed(SeedBootstrap bootstrap, OrgSeed org, AttendanceSeed attendance,
            ApprovalSeed approvals, LedgerSeed ledger, CompanyService companies,
            EmployeeService employees, ApprovalInboxService inbox) {
        this.bootstrap = bootstrap;
        this.org = org;
        this.attendance = attendance;
        this.approvals = approvals;
        this.ledger = ledger;
        this.companies = companies;
        this.employees = employees;
        this.inbox = inbox;
    }

    /**
     * @throws SeedRefusedException if the database already holds an organisation this seed did
     *         not create. Refusing is the whole safety story: the alternative is a demo seed
     *         that a mistyped environment variable can point at a client's installation.
     */
    @Transactional
    public SeedSummary run() {
        UserAccount existingOperator = bootstrap.findOperator().orElse(null);
        if (existingOperator != null) {
            return describeExisting(existingOperator);
        }
        refuseIfInstallationInUse();
        return seed();
    }

    private void refuseIfInstallationInUse() {
        long accounts = bootstrap.accountCount();
        long companyRows = bootstrap.companyCount();
        if (accounts == 0 && companyRows == 0) {
            return;
        }
        throw new SeedRefusedException(
                "이미 사용 중인 데이터베이스에는 데모 자료를 넣지 않습니다. 회사 " + companyRows
                        + "건, 계정 " + accounts + "건이 이미 있습니다. 빈 설치본에서 실행해 주십시오. "
                        + "(Refusing to seed: this database already holds " + companyRows
                        + " company row(s) and " + accounts + " account(s). The demo seed only "
                        + "runs against an empty installation, because the alternative is a "
                        + "misconfigured environment variable writing demo data into somebody's "
                        + "real one.)");
    }

    private SeedSummary seed() {
        SeedWorld world = new SeedWorld();
        world.setOperator(bootstrap.bootstrapOperator());

        org.companies(world);
        bootstrap.writeRepresentation(world);
        org.catalogues(world);
        org.units(world);
        org.people(world);
        org.promotion(world);
        bootstrap.writeAccounts(world);
        org.grants(world);
        bootstrap.writeTemplates(world);
        attendance.statuses(world);
        approvals.run(world);
        int attendanceRecords = attendance.records(world);

        SeedSummary summary = new SeedSummary();
        summary.setAlreadySeeded(false);
        summary.setHqCompanyId(world.companyId(DemoCompany.HQ_CODE));
        summary.setSubsidiaryCompanyId(world.companyId(DemoCompany.SUBSIDIARY_CODE));
        summary.setEmployeeCount(DemoPeople.total());
        summary.setAccountCount(DemoCompany.ACCOUNT_EMPLOYEE_NUMBERS.length);
        summary.setDocumentCount(world.documentCount());
        summary.setAttendanceRecordCount(attendanceRecords);
        summary.setNightShiftOffsetSeconds(attendance.nightShiftOffsetSeconds(world));

        if (ledger.isAvailable()) {
            ledger.run(world);
            summary.setBookId(world.bookId());
            summary.setLedgerBalanced(ledger.balances(world));
        } else {
            summary.setLedgerSkipped(true);
        }

        enrolSignInAccounts(world, summary);
        finishWithInbox(world, summary);
        return summary;
    }

    /**
     * The second run. Nothing is written; the facts are read back through the read services, so
     * "running it twice is safe" is demonstrated rather than asserted - if the demo were half
     * built, these lookups would be the ones to notice.
     */
    private SeedSummary describeExisting(UserAccount operatorAccount) {
        SeedWorld world = new SeedWorld();
        world.setOperator(PermissionPrincipal.master(operatorAccount.id(),
                operatorAccount.displayName(), null));

        for (Company company : companies.list(world.operator(), DemoCompany.TODAY)) {
            world.putCompany(company.code(), company.id());
        }
        int employeeCount = 0;
        for (String companyCode : world.companyCodes()) {
            List<Employee> people = employees.list(world.operator(), world.companyId(companyCode),
                    false, DemoCompany.TODAY);
            employeeCount += people.size();
            for (Employee employee : people) {
                world.putEmployee(employee.employeeNumber(), employee.id());
            }
        }
        int accountCount = 0;
        for (String employeeNumber : DemoCompany.ACCOUNT_EMPLOYEE_NUMBERS) {
            String username = DemoPeople.username(DemoPeople.byNumber(employeeNumber));
            UserAccount account = bootstrap.findAccount(username).orElse(null);
            if (account == null) {
                continue;
            }
            accountCount++;
            world.putAccount(employeeNumber, account.id(), PermissionPrincipal.user(account.id(),
                    account.displayName(), world.employeeId(employeeNumber)));
        }

        SeedSummary summary = new SeedSummary();
        summary.setAlreadySeeded(true);
        summary.setHqCompanyId(world.companyId(DemoCompany.HQ_CODE));
        summary.setSubsidiaryCompanyId(world.companyId(DemoCompany.SUBSIDIARY_CODE));
        summary.setEmployeeCount(employeeCount);
        summary.setAccountCount(accountCount);
        summary.setNightShiftOffsetSeconds(attendance.nightShiftOffsetSeconds(world));
        if (ledger.isAvailable() && ledger.existingBook(world).isPresent()) {
            world.setBookId(ledger.existingBook(world).get().id());
            summary.setBookId(world.bookId());
            summary.setLedgerBalanced(ledger.balances(world));
        } else {
            summary.setLedgerSkipped(!ledger.isAvailable());
        }
        finishWithInbox(world, summary);
        return summary;
    }

    /**
     * Gives the demo's sign-in accounts a working authenticator.
     *
     * <p>There are no passwords in this product, so an account without an enrolled authenticator
     * is an account nobody can use. The secret and the one-time codes are handed back here and
     * printed by the runner; nothing writes them anywhere.
     */
    private void enrolSignInAccounts(SeedWorld world, SeedSummary summary) {
        for (String[] row : DemoCompany.SIGN_IN_AS) {
            String employeeNumber = row[0];
            String username = DemoPeople.username(DemoPeople.byNumber(employeeNumber));
            AuthenticationService.Enrolment enrolment =
                    bootstrap.enrol(world.accountId(employeeNumber));
            summary.addCredential(new SeedSummary.DemoCredential(username, row[1],
                    enrolment.otpauthUri(), enrolment.recoveryCodes()));
        }
    }

    /** The one number a demo really turns on: is there anything in the inbox we tell them to open? */
    private void finishWithInbox(SeedWorld world, SeedSummary summary) {
        PermissionPrincipal demo = world.principal(DemoCompany.DEMO_EMPLOYEE_NUMBER);
        summary.setDemoAccountId(demo.accountId());
        ApprovalInbox loaded = inbox.load(demo, world.companyId(DemoCompany.HQ_CODE),
                demo.accountId());
        summary.setDemoInboxSize(loaded.awaitingMe().size() + loaded.draftedByMe().size()
                + loaded.copiedToMe().size());
    }
}
