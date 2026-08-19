package com.coreintra.app.install;

import com.coreintra.app.config.ApprovalWiring;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.service.ApprovalLineTemplateWriteService;
import com.coreintra.approval.service.ApprovalLineTemplateWriteService.StepDefinition;
import com.coreintra.approval.service.CompanyRepresentationService;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.auth.service.UserAccountService;
import com.coreintra.auth.totp.Base32;
import com.coreintra.auth.totp.TotpGenerator;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.CompanyService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening an empty box, once.
 *
 * <p>Four things in this product have no caller who could authorise them,
 * because on an empty installation there is no caller at all: the first account,
 * the first grants, the first company, and the first 결재선. This service is
 * where those four happen, and it is the only place they can.
 *
 * <h2>What is exempt, and what is not</h2>
 *
 * <p>Exactly two writes here skip the permission evaluator:
 * {@link UserAccountService#createFirstAccount} and
 * {@link InstallationGrants#writeFirstGrants}. Both refuse the instant the
 * installation is non-empty, and both enforce that themselves rather than
 * trusting this class. Everything after them — the company, its factory
 * catalogue, its representation mode, its approval lines — goes through the same
 * services a person would drive from the browser, as the master principal, and
 * is checked by the real evaluator against the grants just written. If those
 * grants are wrong, the installation fails here rather than looking fine and
 * failing on the client's first working day.
 *
 * <h2>Empty means empty</h2>
 *
 * <p>The installer refuses if there is an installation row, <em>or</em> any
 * account, <em>or</em> any company. Keying only on accounts would leave a box
 * that holds a client's companies and no accounts open to a stranger, who would
 * become master of data they have never seen; keying only on the installation
 * row would do the same to any installation that predates this table. Nothing
 * partial can be produced from here in the first place, because the whole thing
 * is one transaction, but the guard does not rely on that.
 *
 * <p>Two simultaneous first requests both read those counts as zero. The
 * primary key on {@code installation} is what actually decides between them: the
 * loser blocks, wakes to a duplicate key, and has everything it wrote rolled
 * back.
 */
@Service
public class InstallationService {

    /** The installation is already open. The endpoint is gone, not merely refused. */
    public static class AlreadyInstalledException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public AlreadyInstalledException(String message) {
            super(message);
        }
    }

    /** There is data here that no installation of this system put there. */
    public static class NotEmptyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public NotEmptyException(String message) {
            super(message);
        }
    }

    private static final String BOOTSTRAP_REASON =
            "설치 시 최초 마스터 계정에 부여된 권한입니다. (Written by the installer for the first "
                    + "master account: no grant existed that could have authorised these, and "
                    + "PermissionGrantService will not issue a wildcard, so they cannot spread. "
                    + "Revoke them once real administrators hold what they need.)";

    private final UserAccountService accounts;
    private final UserAccountRepository accountRows;
    private final MasterAccountService masters;
    private final InstallationGrants bootstrapGrants;
    private final CompanyService companies;
    private final CompanyRepository companyRows;
    private final CompanyDefaults defaults;
    private final CompanyRepresentationService representation;
    private final ApprovalLineTemplateWriteService approvalLines;
    private final AuthenticationService authentication;
    private final InstallationRepository installations;
    private final TotpGenerator totp = new TotpGenerator();

    public InstallationService(UserAccountService accounts, UserAccountRepository accountRows,
            MasterAccountService masters, InstallationGrants bootstrapGrants,
            CompanyService companies, CompanyRepository companyRows, CompanyDefaults defaults,
            CompanyRepresentationService representation,
            ApprovalLineTemplateWriteService approvalLines, AuthenticationService authentication,
            InstallationRepository installations) {
        this.accounts = accounts;
        this.accountRows = accountRows;
        this.masters = masters;
        this.bootstrapGrants = bootstrapGrants;
        this.companies = companies;
        this.companyRows = companyRows;
        this.defaults = defaults;
        this.representation = representation;
        this.approvalLines = approvalLines;
        this.authentication = authentication;
        this.installations = installations;
    }

    /** True while {@code POST /api/v1/install} still exists. */
    @Transactional(readOnly = true)
    public boolean isAvailable() {
        return installations.count() == 0 && accountRows.count() == 0 && companyRows.count() == 0;
    }

    /** The installation row, once there is one. */
    @Transactional(readOnly = true)
    public Optional<InstallationRow> installed() {
        return installations.findById(InstallationRow.ID);
    }

    /**
     * Creates the first company, the first master, and everything that master
     * needs in order to hand the system out.
     *
     * @return the master's credentials, which exist in the returned object and
     *         nowhere else
     * @throws AlreadyInstalledException if this box has been installed
     * @throws NotEmptyException if it holds accounts or companies from anything
     *         else
     */
    @Transactional
    public InstallationResult install(InstallationPlan plan) {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        refuseUnlessEmpty();

        LocalDate today = LocalDate.now();

        // 1. The master. Created without a caller — the one account in the
        //    product's life that can be — then promoted by the service that owns
        //    the "never fewer than one active master" rule, so the flag arrives
        //    the same way every later one will.
        UserAccount master = accounts.createFirstAccount(
                plan.masterUsername(), plan.masterDisplayName());
        masters.promote(master.id());

        // 2. The grants that make the master an administrator rather than a name.
        List<PermissionGrantRow> written = bootstrapGrants.writeFirstGrants(
                master.id(), InstallationGrants.BOOTSTRAP, BOOTSTRAP_REASON);

        // 3. From here on the installer is an ordinary caller. Master status
        //    does not short-circuit the evaluator in this product: everything
        //    below succeeds because of the rows written in step 2, and the
        //    explainer can say so.
        PermissionPrincipal installer =
                PermissionPrincipal.master(master.id(), master.displayName(), null);

        Company company = companies.create(installer, plan.companyCode(), plan.companyNameKo(),
                Company.CompanyKind.HEAD_OFFICE, null, today);
        if (Texts.hasText(plan.companyNameEn())) {
            companies.rename(installer, company.id(), plan.companyNameKo(), plan.companyNameEn(),
                    today);
        }
        if (Texts.hasText(plan.businessRegistrationNumber())
                || Texts.hasText(plan.baseCurrencyCode()) || plan.establishedOn() != null) {
            companies.updateRegistration(installer, company.id(),
                    plan.businessRegistrationNumber(), plan.baseCurrencyCode(),
                    plan.establishedOn(), today);
        }

        int defaultRows = defaults.applyTo(installer, company.id(), today);

        // 4. Without a representation row every submission that reaches a
        //    representative step throws, because the module refuses to guess
        //    whether a company is 각자대표 or 공동대표.
        representation.adopt(installer, company.id(), plan.representationMode(),
                plan.requiredApprovals(), plan.designatedRepresentatives(), today);

        List<String> lineTypes = writeOpeningApprovalLines(installer, company.id(), today);

        // 5. An account with no authenticator is an account nobody can use, and
        //    there are no passwords to fall back on.
        AuthenticationService.Enrolment enrolment = enrolFirstMaster(master.id());

        installations.save(new InstallationRow(master.id(), company.id()));

        return new InstallationResult(company.id(), company.code(), master.id(), master.username(),
                enrolment.secretBase32(), enrolment.otpauthUri(), enrolment.recoveryCodes(),
                describe(written), lineTypes, defaultRows);
    }

    private void refuseUnlessEmpty() {
        if (installations.count() > 0) {
            throw new AlreadyInstalledException(
                    "이 시스템은 이미 설치가 완료되었습니다. 설치 절차는 다시 실행할 수 없습니다. "
                            + "로그인 후 관리자 계정을 추가해 주십시오. (This installation has already "
                            + "been opened. The installer does not run twice: further accounts are "
                            + "created by a master through /api/v1/accounts, and a lost master "
                            + "account is recovered from a backup, not from here.)");
        }
        long accountCount = accountRows.count();
        long companyCount = companyRows.count();
        if (accountCount > 0 || companyCount > 0) {
            throw new NotEmptyException(
                    "빈 데이터베이스에서만 설치할 수 있습니다. 이미 회사 " + companyCount
                            + "건, 계정 " + accountCount + "건이 있습니다. (Refusing to install: this "
                            + "database already holds " + companyCount + " company row(s) and "
                            + accountCount + " account(s). An installation that adopted somebody "
                            + "else's data would hand a stranger a master account over it. "
                            + "Recover the existing installation from its backup instead.)");
        }
    }

    /**
     * The two lines an installation cannot open without.
     *
     * <p>취업규칙 needs 대표자 결재 by law and by {@code EmploymentRules#publish},
     * and 휴가 is the document type the attendance module already knows how to
     * act on when it is approved. Both are company-wide defaults with the
     * smallest line that is always resolvable: a 대표 always exists, because the
     * representation mode says how many there are.
     *
     * <p>The reviewing steps are optional in the technical sense — a step that
     * resolves to nobody is dropped rather than failing the submission — because
     * on the day of installation nobody holds the 인사 직무 and there is no 부장.
     * Making them mandatory would leave the client unable to submit anything
     * until they had built an org chart.
     */
    private List<String> writeOpeningApprovalLines(PermissionPrincipal installer, String companyId,
            LocalDate today) {
        List<StepDefinition> rules = new ArrayList<StepDefinition>();
        rules.add(new StepDefinition(1, ApprovalStepKind.REVIEW,
                RoleExpression.jobFunction("HR", RoleExpression.Domain.COMPANY), true, null,
                "인사 담당자 검토 (dropped while nobody holds the 인사 직무)"));
        rules.add(StepDefinition.base(2, ApprovalStepKind.APPROVE, RoleExpression.representative()));
        approvalLines.define(installer, companyId, EmploymentRulesService.DOCUMENT_TYPE, null,
                "취업규칙 제·개정", "Employment rules", rules, today);

        List<StepDefinition> leave = new ArrayList<StepDefinition>();
        leave.add(new StepDefinition(1, ApprovalStepKind.REVIEW,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), true, null,
                "소속 부서장 검토 (dropped while the drafter's unit has no 부장)"));
        leave.add(StepDefinition.base(2, ApprovalStepKind.APPROVE, RoleExpression.representative()));
        approvalLines.define(installer, companyId,
                ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE, null, "휴가 신청", "Leave request",
                leave, today);

        return Immutables.listOf(EmploymentRulesService.DOCUMENT_TYPE,
                ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE);
    }

    /**
     * Enrols the first authenticator and confirms it in the same breath.
     *
     * <p>Confirmation normally proves possession, and normally the user does it
     * from a signed-in session. The first master has neither: confirming
     * requires a session, a session requires signing in, and signing in requires
     * a confirmed authenticator. Left unconfirmed, the account this installation
     * exists to create could never be used.
     *
     * <p>Possession is not in question here anyway — the secret is being handed
     * to the caller in the response, which is the only copy that will ever
     * exist. So the installer proves it, and the client scans the QR from the
     * response.
     *
     * <p>The code is generated one time step in the past, which the ±1 drift
     * window accepts. Replay protection is per {@code (account, time step)}, so
     * confirming with the <em>current</em> code would burn it and refuse the
     * client's first sign-in for up to thirty seconds — a first impression of
     * "wrong code" on a credential handed over a second earlier.
     */
    private AuthenticationService.Enrolment enrolFirstMaster(String accountId) {
        AuthenticationService.Enrolment enrolment = authentication.beginEnrolment(accountId);
        byte[] secret = Base32.decode(enrolment.secretBase32().replace(" ", ""));
        String code = totp.generateAt(secret,
                System.currentTimeMillis() / 1000L - totp.stepSeconds());
        if (!authentication.confirmEnrolment(accountId, code)) {
            throw new IllegalStateException(
                    "설치 중 인증 앱 등록을 완료하지 못했습니다. (The installer could not confirm the "
                            + "authenticator it had just created for the first master. Nothing is "
                            + "kept: the transaction is rolled back and the box stays installable.)");
        }
        return enrolment;
    }

    private static List<String> describe(List<PermissionGrantRow> rows) {
        List<String> described = new ArrayList<String>(rows.size());
        for (PermissionGrantRow row : rows) {
            PermissionKey key = row.key();
            described.add(key.resource() + ":" + key.action() + "@" + row.scope());
        }
        return described;
    }
}
