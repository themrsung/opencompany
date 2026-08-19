package com.coreintra.core.service;

import com.coreintra.core.org.Employee;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionExplainerService;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The explainer, addressed by person instead of by account.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>{@link PermissionExplainerService} already answers both explainer questions
 * - "what can this user do?" and "why did this one decision come out the way it
 * did?" - and already authorises the inspection itself against {@code
 * admin.permission:read}. None of that is repeated here, and the existing
 * {@code PermissionExplainerController} should keep calling it directly. A
 * second copy of the inspection check would be a second answer to "may I read
 * someone else's authority", and the interesting case is the one where the two
 * disagree.
 *
 * <p>Two things are added, both of them shape rather than policy:
 *
 * <ul>
 *   <li>the org UI lists <em>people</em>, not accounts, so it needs the hop from
 *       employee to account made once and in one place;</li>
 *   <li>a decision has to be asked about a target, and building a target out of
 *       loose request parameters is exactly what {@link OrgTargets} exists to
 *       stop being written per call site.</li>
 * </ul>
 */
@Service
public class EffectivePermissionsService {

    private final PermissionExplainerService explainer;
    private final UserAccountCatalogRepository accounts;
    private final EmployeeService employeeService;

    public EffectivePermissionsService(PermissionExplainerService explainer, UserAccountCatalogRepository accounts,
            EmployeeService employeeService) {
        if (explainer == null) {
            throw new NullPointerException("explainer");
        }
        if (accounts == null) {
            throw new NullPointerException("accounts");
        }
        if (employeeService == null) {
            throw new NullPointerException("employeeService");
        }
        this.explainer = explainer;
        this.accounts = accounts;
        this.employeeService = employeeService;
    }

    /** Straight delegation, kept so callers that hold an account id have one door. */
    @Transactional(readOnly = true)
    public EffectivePermissions explainAccount(PermissionPrincipal caller, String accountId,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        return explainer.explainAccount(caller, Arguments.required(accountId, "accountId"), businessDate);
    }

    /**
     * "What can this person do?", for a UI that knows the person.
     *
     * <p>A person with no account is not an error and not an empty answer: it is a
     * different answer, and saying so is what stops an administrator concluding
     * that someone's grants have vanished.
     */
    @Transactional(readOnly = true)
    public EffectivePermissions explainEmployee(PermissionPrincipal caller, String employeeId,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee employee = employeeService.require(employeeId);
        Optional<UserAccount> account = accounts.findByEmployeeId(employee.id());
        if (!account.isPresent()) {
            throw new IllegalArgumentException(employee.nameKo()
                    + " has no account, so there is nothing signed in to hold permissions");
        }
        return explainer.explainAccount(caller, account.get().id(), businessDate);
    }

    /** Straight delegation for a caller that has already built its own target. */
    @Transactional(readOnly = true)
    public PermissionDecision explainDecision(PermissionPrincipal caller, String accountId, PermissionKey key,
            PermissionTarget target) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        return explainer.explainDecision(caller, Arguments.required(accountId, "accountId"), key, target);
    }

    /**
     * "Why can this person do that to this employee's row?" - the question support
     * calls actually open with.
     */
    @Transactional(readOnly = true)
    public PermissionDecision explainDecisionAboutEmployee(PermissionPrincipal caller, String accountId,
            PermissionKey key, String subjectEmployeeId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee subject = employeeService.require(subjectEmployeeId);
        return explainer.explainDecision(caller, Arguments.required(accountId, "accountId"), key,
                employeeService.target(subject, businessDate));
    }

    /** "Why can this person do that anywhere in this unit?" */
    @Transactional(readOnly = true)
    public PermissionDecision explainDecisionAboutUnit(PermissionPrincipal caller, String accountId,
            PermissionKey key, String companyId, String orgUnitId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        PermissionTarget target = OrgTargets.unit(Arguments.required(companyId, "companyId"),
                Arguments.required(orgUnitId, "orgUnitId"), businessDate, "explainer probe");
        return explainer.explainDecision(caller, Arguments.required(accountId, "accountId"), key, target);
    }
}
