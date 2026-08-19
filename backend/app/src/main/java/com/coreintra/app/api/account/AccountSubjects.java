package com.coreintra.app.api.account;

import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.permission.PrincipalOrgState;
import com.coreintra.core.service.RecordNotFoundException;
import com.coreintra.core.service.UserAccountCatalogRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "this account" into a domain object the evaluator can decide about.
 *
 * <h2>Why the account endpoints need this and the org endpoints do not</h2>
 *
 * <p>{@code CompanyService} and its siblings take the caller and check the
 * evaluator themselves. The three services behind the account surface —
 * {@code AuthenticationService}, {@code ApiKeyService},
 * {@code MasterAccountService} — do not: they take a bare {@code accountId},
 * because they were written for the sign-in path where the caller <em>is</em>
 * the subject and authority is not in question. Exposing them over HTTP puts a
 * third party in the middle, so somebody has to make the decision, and §3 says
 * it is made on the domain object by the one evaluator. This class is that
 * translation and nothing else — it holds no policy of its own and never
 * answers "allowed" without asking.
 *
 * <h2>Authority must reach everywhere the subject stands</h2>
 *
 * <p>A person can hold positions in more than one unit and, in a group with
 * subsidiaries, in more than one company. The check therefore runs once per
 * place they stand and every one of them must pass. The alternative — picking
 * the first company off the set — silently decides that authority over 본사 is
 * authority over somebody who also sits in the 자회사, which is precisely the
 * cross-entity leak multi-entity support exists to avoid.
 *
 * <p>When the subject stands nowhere — a service account, or a leaver whose
 * positions have all closed — there is one target carrying only the owning
 * employee. Then a {@code SELF} grant reaches it if the caller is that person,
 * and otherwise only {@code ALL} does. That is the conservative reading and it
 * is the correct one: nobody's local authority extends over an account that is
 * not in their part of the organisation.
 */
@Service
public class AccountSubjects {

    private final UserAccountCatalogRepository accounts;
    private final OrgDirectory orgDirectory;
    private final PermissionEvaluator evaluator;

    public AccountSubjects(UserAccountCatalogRepository accounts, OrgDirectory orgDirectory,
            PermissionEvaluator evaluator) {
        this.accounts = accounts;
        this.orgDirectory = orgDirectory;
        this.evaluator = evaluator;
    }

    /**
     * Checks that the caller may do {@code key} to this account, and returns it.
     *
     * @throws com.coreintra.core.permission.PermissionDeniedException naming the
     *         permission that was missing
     * @throws RecordNotFoundException if the id names no account
     */
    @Transactional(readOnly = true)
    public UserAccount authorise(PermissionPrincipal caller, String accountId, PermissionKey key,
            LocalDate on, String description) {
        UserAccount subject = require(accountId);
        for (PermissionTarget target : targetsFor(subject, on, description)) {
            // orThrow on the first refusal: the denial names the permission and
            // the place it fell down, which is more use than a summary saying
            // authority was short somewhere.
            evaluator.check(caller, key, target).orThrow();
        }
        return subject;
    }

    /** The account, or a 400 saying the id names nothing. */
    @Transactional(readOnly = true)
    public UserAccount require(String accountId) {
        java.util.Optional<UserAccount> found = accounts.findById(accountId);
        if (!found.isPresent()) {
            throw new RecordNotFoundException("no such account: " + accountId);
        }
        return found.get();
    }

    /** Every place the subject stood on the date; never empty. */
    private List<PermissionTarget> targetsFor(UserAccount subject, LocalDate on,
            String description) {
        PrincipalOrgState state = orgDirectory.resolve(asPrincipal(subject), on);
        List<PermissionTarget> targets = new ArrayList<PermissionTarget>();

        for (String companyId : state.companyIds()) {
            if (state.orgUnitIds().isEmpty()) {
                targets.add(PermissionTarget.builder()
                        .companyId(companyId)
                        .ownerEmployeeId(subject.employeeId())
                        .asOfBusinessDate(on)
                        .description(description)
                        .build());
            }
            for (String orgUnitId : state.orgUnitIds()) {
                targets.add(PermissionTarget.builder()
                        .companyId(companyId)
                        .orgUnitId(orgUnitId)
                        .ownerEmployeeId(subject.employeeId())
                        .asOfBusinessDate(on)
                        .description(description)
                        .build());
            }
        }
        if (targets.isEmpty()) {
            targets.add(PermissionTarget.builder()
                    .ownerEmployeeId(subject.employeeId())
                    .asOfBusinessDate(on)
                    .description(description)
                    .build());
        }
        return targets;
    }

    /**
     * The subject as a principal, only so the directory can place them.
     *
     * <p>Never a master principal even when the subject is a master: this
     * principal is used to look up where somebody stands, not to decide anything
     * on their behalf, and building it with authority attached would be an easy
     * thing to misuse later.
     */
    private static PermissionPrincipal asPrincipal(UserAccount subject) {
        return PermissionPrincipal.user(subject.id(), subject.displayName(), subject.employeeId());
    }
}
