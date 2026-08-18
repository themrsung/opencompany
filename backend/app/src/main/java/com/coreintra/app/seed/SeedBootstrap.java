package com.coreintra.app.seed;

import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.ApprovalLineTemplateRepository;
import com.coreintra.approval.repository.ApprovalTemplateStepRepository;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.app.config.ApprovalWiring;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.totp.Base32;
import com.coreintra.auth.totp.TotpGenerator;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The four things the seed cannot ask a service to do for it.
 *
 * <p>Everything else in the demo goes through the same services the REST layer calls, with a
 * real {@link PermissionPrincipal}, because a seed that writes rows proves nothing and rots
 * silently. These four have no service to go through at all, and each is a gap in the product
 * rather than a shortcut taken here:
 *
 * <ol>
 *   <li><b>User accounts.</b> Nothing in the application creates one. {@code AuthenticationService}
 *       enrols an account that already exists, {@code MasterAccountService} promotes and demotes
 *       one, {@code AccountSubjects} reads one. There is no create path, from REST or anywhere
 *       else, so a fresh installation cannot produce its first account.</li>
 *   <li><b>The first grants.</b> {@code PermissionGrantService} refuses to hand out a permission
 *       the caller does not already hold - which is right, and which means the first grant in an
 *       installation cannot come from it. Somebody has to write the operator's row.</li>
 *   <li><b>The representation mode.</b> {@code company_representation} is read by
 *       {@code JpaRepresentationDirectory} and written by nothing. Without a row, every
 *       submission throws, because the module refuses to guess whether a company is 각자대표 or
 *       공동대표.</li>
 *   <li><b>Approval line templates.</b> §6.8 of the brief is unbuilt: the tables exist, the store
 *       reads them, no code writes them.</li>
 * </ol>
 *
 * <p>They are isolated here, and only here, so that the day any of those four grows a service the
 * change is one file. The rest of the seed is honest about what a real user can do.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
class SeedBootstrap {

    private final UserAccountRepository accounts;
    private final CompanyRepository companies;
    private final PermissionGrantRepository grants;
    private final CompanyRepresentationRepository representations;
    private final ApprovalLineTemplateRepository templates;
    private final ApprovalTemplateStepRepository templateSteps;
    private final AuthenticationService authentication;
    private final TotpGenerator totp = new TotpGenerator();

    SeedBootstrap(UserAccountRepository accounts, CompanyRepository companies,
            PermissionGrantRepository grants,
            CompanyRepresentationRepository representations,
            ApprovalLineTemplateRepository templates,
            ApprovalTemplateStepRepository templateSteps,
            AuthenticationService authentication) {
        this.accounts = accounts;
        this.companies = companies;
        this.grants = grants;
        this.representations = representations;
        this.templates = templates;
        this.templateSteps = templateSteps;
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
     * The operator account and its grants: the one place in the demo where authority comes from
     * nowhere. Every other permission in the installation is handed out by this account through
     * {@code PermissionGrantService}, and can therefore be explained by the effective-permissions
     * explainer down to this row.
     */
    PermissionPrincipal bootstrapOperator() {
        String accountId = seedId("account", DemoCompany.OPERATOR_USERNAME);
        UserAccount account = accounts.findByUsername(DemoCompany.OPERATOR_USERNAME).orElse(null);
        if (account == null) {
            // A person's account rather than a service account: master status is refused to
            // service accounts, on the grounds that a standing unattended god-key is exactly
            // what the temporary-master feature exists to avoid.
            account = new UserAccount(accountId, DemoCompany.OPERATOR_USERNAME,
                    DemoCompany.OPERATOR_DISPLAY_NAME, UserAccount.AccountKind.USER);
        }
        account.setMaster(true);
        accounts.save(account);

        List<PermissionKey> keys = SeedPermissions.operatorKeys();
        for (PermissionKey key : keys) {
            writeGrant(GrantSource.USER_ACCOUNT, account.id(), key, PermissionScope.ALL, true,
                    "데모 시드 운영 계정의 초기 권한입니다.");
        }
        // Master status does not short-circuit the evaluator in this product, so the operator
        // works because of the rows above and can be explained by them, like anyone else.
        return PermissionPrincipal.master(account.id(), DemoCompany.OPERATOR_DISPLAY_NAME, null);
    }

    /**
     * Sign-in accounts for the people the demo needs to act as. The account id is derived from
     * the username so that a second run finds the same rows instead of minting new ones.
     */
    void writeAccounts(SeedWorld world) {
        for (String employeeNumber : DemoCompany.ACCOUNT_EMPLOYEE_NUMBERS) {
            String[] person = DemoPeople.byNumber(employeeNumber);
            String username = DemoPeople.username(person);
            String displayName = person[1];
            String accountId = seedId("account", username);
            UserAccount account = accounts.findByUsername(username).orElse(null);
            if (account == null) {
                account = new UserAccount(accountId, username, displayName,
                        UserAccount.AccountKind.USER);
            }
            account.linkToEmployee(world.employeeId(employeeNumber));
            // 대표이사 holds master: a real installation's master account belongs to a person who
            // can answer for it, not to a shared operator login.
            account.setMaster(DemoCompany.MASTER_EMPLOYEE_NUMBER.equals(employeeNumber));
            accounts.save(account);
            world.putAccount(employeeNumber, account.id(),
                    PermissionPrincipal.user(account.id(), displayName,
                            world.employeeId(employeeNumber)));
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
        writeRepresentation(world.companyId(DemoCompany.HQ_CODE), RepresentationMode.joint(
                DemoCompany.HQ_REQUIRED_REPRESENTATIVE_APPROVALS,
                DemoCompany.HQ_DESIGNATED_REPRESENTATIVES));
        writeRepresentation(world.companyId(DemoCompany.SUBSIDIARY_CODE),
                RepresentationMode.several(DemoCompany.SUBSIDIARY_DESIGNATED_REPRESENTATIVES));
    }

    private void writeRepresentation(String companyId, RepresentationMode mode) {
        List<CompanyRepresentationEntity> existing =
                representations.findByCompanyIdOrderByEffectiveFromDesc(companyId);
        if (!existing.isEmpty()) {
            return;
        }
        representations.save(new CompanyRepresentationEntity(
                seedId("representation", companyId), companyId, mode,
                DemoCompany.REPRESENTATION_EFFECTIVE_FROM, null));
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

        List<Step> expense = new ArrayList<Step>();
        expense.add(Step.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), false));
        expense.add(Step.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.rank("ISA", RoleExpression.Domain.DRAFTER_UNIT_PARENT), false));
        expense.add(Step.base(3, ApprovalStepKind.CC,
                RoleExpression.rank("GWAJANG", RoleExpression.Domain.DRAFTER_UNIT), true));
        expense.add(Step.threshold(4, ApprovalStepKind.APPROVE, RoleExpression.representative(),
                new BigDecimal("5000000"),
                "5,000,000원을 넘는 지출은 공동대표 승인을 받습니다."));
        write(hq, DemoCompany.DOC_TYPE_EXPENSE, "지출결의서 기본 결재선", expense);

        List<Step> purchase = new ArrayList<Step>();
        purchase.add(Step.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), false));
        purchase.add(Step.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.rank("ISA", RoleExpression.Domain.DRAFTER_UNIT_PARENT), false));
        purchase.add(Step.base(3, ApprovalStepKind.CC,
                RoleExpression.jobFunction("ACCOUNTING", RoleExpression.Domain.COMPANY), true));
        write(hq, DemoCompany.DOC_TYPE_PURCHASE, "구매품의서 기본 결재선", purchase);

        List<Step> leave = new ArrayList<Step>();
        leave.add(Step.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), false));
        leave.add(Step.base(2, ApprovalStepKind.APPROVE,
                RoleExpression.jobFunction("HR", RoleExpression.Domain.COMPANY), false));
        write(hq, ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE, "휴가신청서 결재선", leave);

        List<Step> rules = new ArrayList<Step>();
        rules.add(Step.base(1, ApprovalStepKind.REVIEW,
                RoleExpression.jobFunction("HR", RoleExpression.Domain.COMPANY), false));
        rules.add(Step.base(2, ApprovalStepKind.APPROVE, RoleExpression.representative(), false));
        write(hq, EmploymentRulesService.DOCUMENT_TYPE, "취업규칙 개정 결재선", rules);

        // 자회사 is 각자대표, so the same shape of line needs only one signature to complete.
        List<Step> subsidiaryExpense = new ArrayList<Step>();
        subsidiaryExpense.add(Step.base(1, ApprovalStepKind.APPROVE,
                RoleExpression.representative(), false));
        write(subsidiary, DemoCompany.DOC_TYPE_EXPENSE, "지출결의서 결재선", subsidiaryExpense);
    }

    private void write(String companyId, String documentType, String nameKo, List<Step> steps) {
        String templateId = seedId("template", companyId + ":" + documentType);
        if (templates.findById(templateId).isPresent()) {
            return;
        }
        templates.save(new ApprovalLineTemplateEntity(templateId, companyId, documentType, null,
                nameKo));
        for (Step step : steps) {
            templateSteps.save(new ApprovalTemplateStepEntity(
                    seedId("template-step", templateId + ":" + step.position), templateId,
                    step.position, step.kind, step.role.toString(), step.optional,
                    step.minimumAmount, step.rationale));
        }
    }

    private void writeGrant(GrantSource source, String sourceId, PermissionKey key,
            PermissionScope scope, boolean allow, String reason) {
        List<PermissionGrantRow> existing = grants.findBySourceAndSourceId(source, sourceId);
        for (PermissionGrantRow row : existing) {
            if (row.key().equals(key) && row.scope() == scope && row.isAllow() && !row.isRevoked()) {
                return;
            }
        }
        PermissionGrantRow row = new PermissionGrantRow(UUID.randomUUID().toString(), source,
                sourceId, key, scope, allow);
        row.setGrantedBy(sourceId);
        row.setReason(reason);
        grants.save(row);
    }

    /**
     * A stable id for a row the seed owns. Ids are UUID strings generated in Java, and naming
     * them after what they describe means a second run recognises its own work rather than
     * creating a second copy of it.
     */
    static String seedId(String kind, String name) {
        return UUID.nameUUIDFromBytes(("coreintra-seed:" + kind + ":" + name)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** One row of an approval line template, before it has a template to belong to. */
    private static final class Step {

        private final int position;
        private final ApprovalStepKind kind;
        private final RoleExpression role;
        private final boolean optional;
        private final BigDecimal minimumAmount;
        private final String rationale;

        private Step(int position, ApprovalStepKind kind, RoleExpression role, boolean optional,
                BigDecimal minimumAmount, String rationale) {
            this.position = position;
            this.kind = kind;
            this.role = role;
            this.optional = optional;
            this.minimumAmount = minimumAmount;
            this.rationale = rationale;
        }

        static Step base(int position, ApprovalStepKind kind, RoleExpression role,
                boolean optional) {
            return new Step(position, kind, role, optional, null, null);
        }

        static Step threshold(int position, ApprovalStepKind kind, RoleExpression role,
                BigDecimal minimumAmount, String rationale) {
            return new Step(position, kind, role, false, minimumAmount, rationale);
        }
    }
}
