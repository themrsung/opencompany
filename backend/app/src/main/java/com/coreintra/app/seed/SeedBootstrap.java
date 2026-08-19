package com.coreintra.app.seed;

import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.service.ApprovalLineTemplateWriteService;
import com.coreintra.approval.service.ApprovalLineTemplateWriteService.StepDefinition;
import com.coreintra.approval.service.CompanyRepresentationService;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.app.config.ApprovalWiring;
import com.coreintra.app.install.InstallationGrants;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.auth.service.UserAccountService;
import com.coreintra.auth.totp.Base32;
import com.coreintra.auth.totp.TotpGenerator;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * What the demo seed needs before it can behave like a user, and where it gets it.
 *
 * <p>This class used to write four kinds of row directly — accounts, the first grants, the
 * representation mode and the approval line templates — because nothing in the product could.
 * All four now have a service, so all four are calls:
 *
 * <ul>
 *   <li>{@link UserAccountService} creates accounts, including the one an empty installation has
 *       no caller to authorise;</li>
 *   <li>{@link InstallationGrants} writes the first grants, and refuses once any grant
 *       exists;</li>
 *   <li>{@link CompanyRepresentationService} records 각자대표 / 공동대표;</li>
 *   <li>{@link ApprovalLineTemplateWriteService} writes 결재선 서식.</li>
 * </ul>
 *
 * <p>What is left here is demo <em>data</em>: which people get accounts, which company is
 * 공동대표 and with what quorum, and the five approval lines the demo's documents travel down.
 * The seed is now honest end to end — every row it produces could have been produced by
 * somebody clicking, or by the installer on first boot.
 *
 * <p>It is not gone entirely because the demo's shape is its own: a real installation opens with
 * {@code POST /api/v1/install}, which creates one company with the factory defaults, whereas the
 * demo wants two legal entities, a contrasting representation mode on each and a threshold rule
 * to show off. Those choices belong to the demo, and this is where the demo keeps them.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
class SeedBootstrap {

    private final UserAccountRepository accounts;
    private final CompanyRepository companies;
    private final UserAccountService accountService;
    private final MasterAccountService masters;
    private final InstallationGrants firstGrants;
    private final CompanyRepresentationService representation;
    private final ApprovalLineTemplateWriteService approvalLines;
    private final AuthenticationService authentication;
    private final TotpGenerator totp = new TotpGenerator();

    SeedBootstrap(UserAccountRepository accounts, CompanyRepository companies,
            UserAccountService accountService, MasterAccountService masters,
            InstallationGrants firstGrants, CompanyRepresentationService representation,
            ApprovalLineTemplateWriteService approvalLines,
            AuthenticationService authentication) {
        this.accounts = accounts;
        this.companies = companies;
        this.accountService = accountService;
        this.masters = masters;
        this.firstGrants = firstGrants;
        this.representation = representation;
        this.approvalLines = approvalLines;
        this.authentication = authentication;
    }

    Optional<UserAccount> findOperator() {
        return accounts.findByUsername(DemoCompany.OPERATOR_USERNAME);
    }

    Optional<UserAccount> findAccount(String username) {
        return accounts.findByUsername(username);
    }

    long accountCount() {
        return accounts.count();
    }

    long companyCount() {
        return companies.count();
    }

    /**
     * The operator account and its grants — the demo taking the same two steps the installer
     * takes, for the same reason: on an empty installation there is nobody to ask.
     *
     * <p>Both steps refuse if the box is not empty, and they refuse in {@code UserAccountService}
     * and {@code InstallationGrants} rather than here, so the demo cannot talk its way past them.
     * Every other permission in the installation is handed out by this account through
     * {@code PermissionGrantService}, and can therefore be explained by the effective-permissions
     * explainer down to these rows.
     *
     * <p>The keys are the concrete list in {@link SeedPermissions} rather than the installer's
     * wildcards, deliberately: the demo exists to show what an explainer looks like on a real
     * installation, and "because the operator holds hr.employee:read at ALL" is a better answer
     * than "because the operator holds hr.*".
     */
    PermissionPrincipal bootstrapOperator() {
        UserAccount account = accounts.findByUsername(DemoCompany.OPERATOR_USERNAME).orElse(null);
        if (account == null) {
            // A person's account rather than a service account: master status is refused to
            // service accounts, on the grounds that a standing unattended god-key is exactly
            // what the temporary-master feature exists to avoid.
            account = accountService.createFirstAccount(DemoCompany.OPERATOR_USERNAME,
                    DemoCompany.OPERATOR_DISPLAY_NAME);
            masters.promote(account.id());
            firstGrants.writeFirstGrants(account.id(), SeedPermissions.operatorKeys(),
                    "데모 시드 운영 계정의 초기 권한입니다.");
        }
        // Master status does not short-circuit the evaluator in this product, so the operator
        // works because of the rows above and can be explained by them, like anyone else.
        return PermissionPrincipal.master(account.id(), DemoCompany.OPERATOR_DISPLAY_NAME, null);
    }

    /**
     * Sign-in accounts for the people the demo needs to act as, created by the operator through
     * the same service an administrator would use — so the demo fails the day account creation
     * grows a rule it does not satisfy.
     */
    void writeAccounts(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        for (String employeeNumber : DemoCompany.ACCOUNT_EMPLOYEE_NUMBERS) {
            String[] person = DemoPeople.byNumber(employeeNumber);
            String username = DemoPeople.username(person);
            String displayName = person[1];
            String employeeId = world.employeeId(employeeNumber);

            UserAccount account = accounts.findByUsername(username).orElse(null);
            if (account == null) {
                account = accountService.create(operator, username, displayName,
                        UserAccount.AccountKind.USER, employeeId, DemoCompany.TODAY);
            }
            if (DemoCompany.MASTER_EMPLOYEE_NUMBER.equals(employeeNumber)) {
                // 대표이사 holds master: a real installation's master account belongs to a
                // person who can answer for it, not to a shared operator login.
                masters.promote(account.id());
            }
            world.putAccount(employeeNumber, account.id(),
                    PermissionPrincipal.user(account.id(), displayName, employeeId));
        }
    }

    /**
     * Enrols an authenticator for a seeded account and returns what is shown once.
     *
     * <p>This is the product's own enrolment path, run to completion: the seed generates the
     * first code from the secret it was handed and confirms it, because a demo installation
     * whose accounts cannot sign in is not a demo. The codes are shown once, exactly as the API
     * shows them, and only ever on this installation's console.
     */
    AuthenticationService.Enrolment enrol(String accountId) {
        AuthenticationService.Enrolment enrolment = authentication.beginEnrolment(accountId);
        byte[] secret = Base32.decode(enrolment.secretBase32().replace(" ", ""));
        String code = totp.generateAt(secret, System.currentTimeMillis() / 1000L);
        if (!authentication.confirmEnrolment(accountId, code)) {
            throw new IllegalStateException(
                    "계정 " + accountId + " 의 인증기 등록을 확인하지 못하였습니다. (The seeded "
                            + "account's authenticator enrolment could not be confirmed, so the "
                            + "demo would have no way in.)");
        }
        return enrolment;
    }

    /**
     * The representation arrangement each company operates under. 본사 is 공동대표 with a real
     * quorum - two signatures of three - because that is the configuration a change is most
     * likely to break and the one a demo should show; 자회사 is 각자대표, where any one of the two
     * suffices, so the contrast is visible on the same screen.
     */
    void writeRepresentation(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        representation.adopt(operator, world.companyId(DemoCompany.HQ_CODE),
                RepresentationMode.joint(DemoCompany.HQ_REQUIRED_REPRESENTATIVE_APPROVALS,
                        DemoCompany.HQ_DESIGNATED_REPRESENTATIVES),
                DemoCompany.REPRESENTATION_EFFECTIVE_FROM);
        representation.adopt(operator, world.companyId(DemoCompany.SUBSIDIARY_CODE),
                RepresentationMode.several(DemoCompany.SUBSIDIARY_DESIGNATED_REPRESENTATIVES),
                DemoCompany.REPRESENTATION_EFFECTIVE_FROM);
    }

    /**
     * The company-default approval lines, one per document type.
     *
     * <p>They are written as company defaults - {@code orgUnitId} null - because that is the
     * shape a client starts from: one line per document type, refined per unit later. The
     * expense line carries a threshold rule so the demo has a document that picks up the
     * 공동대표 step purely because of its amount.
     */
    void writeTemplates(SeedWorld world) {
        String hq = world.companyId(DemoCompany.HQ_CODE);
        String subsidiary = world.companyId(DemoCompany.SUBSIDIARY_CODE);

        List<StepDefinition> expense = new ArrayList<StepDefinition>();
        expense.add(StepDefinition.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        expense.add(StepDefinition.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.rank("ISA", RoleExpression.Domain.DRAFTER_UNIT_PARENT)));
        expense.add(new StepDefinition(3, ApprovalStepKind.CC,
                RoleExpression.rank("GWAJANG", RoleExpression.Domain.DRAFTER_UNIT), true, null,
                null));
        expense.add(StepDefinition.above(new BigDecimal("5000000"), 4, ApprovalStepKind.APPROVE,
                RoleExpression.representative(),
                "5,000,000원을 넘는 지출은 공동대표 승인을 받습니다."));
        write(world, hq, DemoCompany.DOC_TYPE_EXPENSE, "지출결의서 기본 결재선", expense);

        List<StepDefinition> purchase = new ArrayList<StepDefinition>();
        purchase.add(StepDefinition.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        purchase.add(StepDefinition.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.rank("ISA", RoleExpression.Domain.DRAFTER_UNIT_PARENT)));
        purchase.add(new StepDefinition(3, ApprovalStepKind.CC,
                RoleExpression.jobFunction("ACCOUNTING", RoleExpression.Domain.COMPANY), true,
                null, null));
        write(world, hq, DemoCompany.DOC_TYPE_PURCHASE, "구매품의서 기본 결재선", purchase);

        List<StepDefinition> leave = new ArrayList<StepDefinition>();
        leave.add(StepDefinition.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        leave.add(StepDefinition.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.jobFunction("HR", RoleExpression.Domain.COMPANY)));
        write(world, hq, ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE, "휴가신청서 결재선", leave);

        List<StepDefinition> rules = new ArrayList<StepDefinition>();
        rules.add(StepDefinition.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.jobFunction("HR", RoleExpression.Domain.COMPANY)));
        rules.add(StepDefinition.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.representative()));
        write(world, hq, EmploymentRulesService.DOCUMENT_TYPE, "취업규칙 개정 결재선", rules);

        // 자회사 is 각자대표, so the same shape of line needs only one signature to complete.
        List<StepDefinition> subsidiaryExpense = new ArrayList<StepDefinition>();
        subsidiaryExpense.add(StepDefinition.base(1, ApprovalStepKind.APPROVE,
                RoleExpression.representative()));
        write(world, subsidiary, DemoCompany.DOC_TYPE_EXPENSE, "지출결의서 결재선",
                subsidiaryExpense);
    }

    private void write(SeedWorld world, String companyId, String documentType, String nameKo,
            List<StepDefinition> steps) {
        approvalLines.define(world.operator(), companyId, documentType, null, nameKo, null, steps,
                DemoCompany.TODAY);
    }
}
