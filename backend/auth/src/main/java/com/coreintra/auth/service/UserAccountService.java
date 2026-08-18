package com.coreintra.auth.service;

import com.coreintra.compat.Texts;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EmployeeService;
import com.coreintra.core.service.OrgPermissions;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way an account comes into existence.
 *
 * <p>Until this class there was none. {@link AuthenticationService} enrols an
 * authenticator on an account that already exists, {@link MasterAccountService}
 * promotes and demotes one, the org services read one — and nothing, from REST
 * or anywhere else, created one. A fresh installation therefore had no way to
 * produce its first account, and an installation in use had no way to produce
 * its next.
 *
 * <h2>There is no password parameter, and there never will be</h2>
 *
 * <p>Creating an account creates an identity, not a credential. The account
 * cannot sign in until {@link AuthenticationService#beginEnrolment} has minted a
 * TOTP secret for it and someone has proved possession of that secret. A
 * "temporary password" here would reintroduce the reset flow ADR 0006 exists to
 * delete.
 *
 * <h2>Two doors, and only one of them is open on a running installation</h2>
 *
 * <p>{@link #create} is the ordinary path and is permission-checked like
 * everything else. {@link #createFirstAccount} is the installation bootstrap:
 * it takes no caller, because on an empty box there is nobody to be, and it
 * refuses the moment a single account row exists. The refusal lives here rather
 * than in the installer because this class owns the table the rule is about —
 * an installer that forgot the check would otherwise be able to mint a master
 * on a client's populated database.
 *
 * <h2>Reach</h2>
 *
 * <p>An account attached to an employee is administered where that employee
 * stands, so a company-scoped administrator can give their own staff accounts.
 * An account attached to nobody — a service account, or the first master before
 * anyone has been hired — belongs to the installation as a whole, and only an
 * {@code ALL}-scoped grant reaches it. Defaulting the second case to some
 * company would hand every company administrator the ability to mint API keys
 * for the installation.
 */
@Service
public class UserAccountService {

    /** Refusing to create an account whose username or employee is already taken. */
    public static class AccountConflictException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public AccountConflictException(String message) {
            super(message);
        }
    }

    /**
     * Refusing to bootstrap an account on an installation that already has one.
     *
     * <p>Not a permission failure — there is no caller to deny — so it is not a
     * {@code PermissionDeniedException}. It is a statement that the door this
     * method is closed for good.
     */
    public static class InstallationNotEmptyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public InstallationNotEmptyException(String message) {
            super(message);
        }
    }

    private final UserAccountRepository accounts;
    private final EmployeeService employees;
    private final MasterAccountService masters;
    private final PermissionEvaluator evaluator;

    public UserAccountService(UserAccountRepository accounts, EmployeeService employees,
            MasterAccountService masters, PermissionEvaluator evaluator) {
        if (accounts == null) {
            throw new NullPointerException("accounts");
        }
        if (employees == null) {
            throw new NullPointerException("employees");
        }
        if (masters == null) {
            throw new NullPointerException("masters");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.accounts = accounts;
        this.employees = employees;
        this.masters = masters;
        this.evaluator = evaluator;
    }

    /**
     * Creates an account.
     *
     * <p>Never a master: {@link UserAccount#isMaster()} is set by
     * {@link MasterAccountService#promote}, which owns the "never fewer than one
     * active master" invariant and refuses to give the flag to a service
     * account. Splitting creation from promotion means the audit trail shows two
     * decisions, which is what they are.
     *
     * @param employeeId the person this account belongs to, or null for a
     *        service account or an administrator who is not on the payroll
     * @throws AccountConflictException if the username is taken, or the employee
     *         already has an account
     */
    @Transactional
    public UserAccount create(PermissionPrincipal caller, String username, String displayName,
            UserAccount.AccountKind kind, String employeeId, LocalDate businessDate) {
        requireCaller(caller, businessDate);
        String cleanUsername = required(username, "username");
        String cleanDisplayName = required(displayName, "displayName");
        String cleanEmployeeId = Texts.isBlank(employeeId) ? null : Texts.strip(employeeId);
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (kind != UserAccount.AccountKind.USER && cleanEmployeeId != null) {
            // The database says the same thing (user_account_employee_matches_kind).
            // Saying it here names the mistake instead of surfacing a constraint.
            throw new IllegalArgumentException(
                    kind + " 계정은 직원과 연결할 수 없습니다. (" + kind + " accounts are never a "
                            + "person; only a USER account may be linked to an employee.)");
        }

        evaluator.check(caller, OrgPermissions.ACCOUNT_CREATE,
                reachOf(caller, cleanEmployeeId, businessDate, "creating account " + cleanUsername))
                .orThrow();

        refuseIfUsernameTaken(cleanUsername);
        if (cleanEmployeeId != null) {
            refuseIfEmployeeAlreadyHasAccount(cleanEmployeeId);
        }

        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(), cleanUsername, cleanDisplayName, kind);
        if (cleanEmployeeId != null) {
            account.linkToEmployee(cleanEmployeeId);
        }
        return accounts.save(account);
    }

    /**
     * The first account on an empty installation, created with no caller.
     *
     * <p>This is the one write in the product that no permission authorises,
     * and it is deliberately the narrowest one available: a single {@code USER}
     * account, no employee, no master flag, refused outright once any account
     * row exists. Promotion to master and the bootstrap grants are separate
     * decisions taken by the installer, so this method on its own cannot open
     * anything.
     *
     * <p>The count is not a race-proof guard on its own — two concurrent callers
     * can both read zero. It is the readable statement of the rule; the
     * installation row's primary key in {@code V15__installation.sql} and
     * {@code user_account_username_unique} are what actually serialise a race,
     * and both fail the loser's whole transaction.
     *
     * @throws InstallationNotEmptyException if the installation already has an
     *         account, active or not — deactivating every account must not
     *         reopen this door
     */
    @Transactional
    public UserAccount createFirstAccount(String username, String displayName) {
        long existing = accounts.count();
        if (existing > 0) {
            throw new InstallationNotEmptyException(
                    "이 설치본에는 이미 계정이 " + existing + "개 있으므로 최초 계정을 만들 수 없습니다. "
                            + "(This installation already has " + existing + " account(s), so the "
                            + "first-account path is closed. Accounts are created by an "
                            + "administrator holding admin.account:create.)");
        }
        String cleanUsername = required(username, "username");
        refuseIfUsernameTaken(cleanUsername);
        return accounts.save(new UserAccount(UUID.randomUUID().toString(), cleanUsername,
                required(displayName, "displayName"), UserAccount.AccountKind.USER));
    }

    /**
     * Attaches an account to a person.
     *
     * <p>Checked against the employee being attached <em>and</em> against
     * wherever the account already reaches, because linking is how an account
     * would move between companies: checking only the destination would let an
     * administrator adopt an account they could not otherwise touch.
     */
    @Transactional
    public UserAccount linkToEmployee(PermissionPrincipal caller, String accountId,
            String employeeId, LocalDate businessDate) {
        requireCaller(caller, businessDate);
        UserAccount account = require(accountId);
        String cleanEmployeeId = required(employeeId, "employeeId");

        if (account.kind() != UserAccount.AccountKind.USER) {
            throw new IllegalArgumentException(
                    account.kind() + " 계정은 직원과 연결할 수 없습니다. (" + account.kind()
                            + " accounts are never a person.)");
        }
        evaluator.check(caller, OrgPermissions.ACCOUNT_UPDATE,
                reachOf(caller, account.employeeId(), businessDate,
                        "linking account " + account.username())).orThrow();
        evaluator.check(caller, OrgPermissions.ACCOUNT_UPDATE,
                reachOf(caller, cleanEmployeeId, businessDate,
                        "linking account " + account.username() + " to employee "
                                + cleanEmployeeId)).orThrow();

        if (!cleanEmployeeId.equals(account.employeeId())) {
            refuseIfEmployeeAlreadyHasAccount(cleanEmployeeId);
        }
        account.linkToEmployee(cleanEmployeeId);
        return accounts.save(account);
    }

    /**
     * Suspends an account without deleting it.
     *
     * <p>The last-master rule is not restated here: {@link MasterAccountService}
     * owns it, counts under a lock, and refuses with a message that says how to
     * proceed. Deactivating the last master locks everyone out exactly as
     * effectively as demoting them, which is why it is refused on the same
     * terms.
     */
    @Transactional
    public UserAccount deactivate(PermissionPrincipal caller, String accountId,
            LocalDate businessDate) {
        UserAccount account = authoriseUpdate(caller, accountId, businessDate, "deactivating");
        masters.deactivate(account.id());
        return require(account.id());
    }

    /** Puts a suspended account back into service. Enrolment and grants are untouched by both. */
    @Transactional
    public UserAccount reactivate(PermissionPrincipal caller, String accountId,
            LocalDate businessDate) {
        UserAccount account = authoriseUpdate(caller, accountId, businessDate, "reactivating");
        account.reactivate();
        return accounts.save(account);
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return accounts.findByUsername(Texts.strip(username == null ? "" : username));
    }

    /** True while {@link #createFirstAccount} is still open. */
    @Transactional(readOnly = true)
    public boolean hasNoAccounts() {
        return accounts.count() == 0;
    }

    private UserAccount authoriseUpdate(PermissionPrincipal caller, String accountId,
            LocalDate businessDate, String what) {
        requireCaller(caller, businessDate);
        UserAccount account = require(accountId);
        evaluator.check(caller, OrgPermissions.ACCOUNT_UPDATE,
                reachOf(caller, account.employeeId(), businessDate,
                        what + " account " + account.username())).orThrow();
        return account;
    }

    /**
     * Where a grant has to reach to administer this account.
     *
     * <p>Resolving the employee goes through {@link EmployeeService#read}, so
     * the caller also has to be able to see the person. That is not an extra
     * hurdle for its own sake: an administrator who cannot read an employee has
     * no business deciding whether that employee may sign in.
     */
    private PermissionTarget reachOf(PermissionPrincipal caller, String employeeId,
            LocalDate businessDate, String description) {
        if (employeeId == null) {
            return PermissionTarget.installationWide(businessDate);
        }
        Employee employee = employees.read(caller, employeeId, businessDate);
        return PermissionTarget.builder()
                .companyId(employee.companyId())
                .ownerEmployeeId(employee.id())
                .asOfBusinessDate(businessDate)
                .description(description)
                .build();
    }

    private void refuseIfUsernameTaken(String username) {
        if (accounts.findByUsername(username).isPresent()) {
            throw new AccountConflictException(
                    "이미 사용 중인 사용자 이름입니다: " + username
                            + " (That username already belongs to an account. Usernames are how "
                            + "people sign in, so they are unique across the installation.)");
        }
    }

    /**
     * One person, one account.
     *
     * <p>A scan rather than a query, because {@code UserAccountRepository} has
     * no finder by employee and this runs only when an account is created or
     * relinked, on a table with one row per member of staff. The database
     * enforces it too ({@code user_account_employee_unique}); this exists so the
     * answer names the existing account instead of being a constraint name.
     */
    private void refuseIfEmployeeAlreadyHasAccount(String employeeId) {
        for (UserAccount existing : accounts.findAll()) {
            if (employeeId.equals(existing.employeeId())) {
                throw new AccountConflictException(
                        "이 직원에게는 이미 계정 \"" + existing.username() + "\"이(가) 있습니다. "
                                + "(This employee already has the account \"" + existing.username()
                                + "\". One person, one account: a second one would split their "
                                + "approval history in two.)");
            }
        }
    }

    private UserAccount require(String accountId) {
        Optional<UserAccount> found = accounts.findById(required(accountId, "accountId"));
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no such account: " + accountId);
        }
        return found.get();
    }

    private static void requireCaller(PermissionPrincipal caller, LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
    }

    private static String required(String value, String field) {
        if (Texts.isBlank(value)) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return Texts.strip(value);
    }
}
