package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing the grant book.
 *
 * <h2>You cannot hand out what you do not hold</h2>
 *
 * <p>Every write here passes two checks against the same target: {@code
 * admin.permission:grant}, which says the caller administers permissions at all,
 * and <em>the permission being granted itself</em>, which says the caller could
 * have exercised it. Without the second check, the first one is a root account
 * with extra steps: anyone who could administer permissions could write
 * themselves an {@code accounting.entry:post} grant and post the ledger.
 *
 * <p>The second check is deliberately conservative. A grant attached to a rank
 * reaches every holder of that rank across the company, so it is checked at
 * company level; a grant scoped {@link PermissionScope#ALL} reaches every row in
 * the installation, so it is checked installation-wide. Granting narrowly still
 * requires holding broadly, which errs towards refusing an escalation.
 *
 * <p>Revocation is gated the same way, for the mirror-image reason: an admin who
 * cannot post the ledger should not be able to strip the right from the person
 * who can.
 *
 * <h2>Concrete keys only</h2>
 *
 * <p>Wildcard grants ({@code accounting.*:post}) exist in the model but are not
 * issued here. The escalation gate has to ask the evaluator "may the caller do
 * this?", and {@link PermissionKey} refuses a wildcard as a <em>required</em>
 * permission - correctly, since "may I do anything in accounting?" has no
 * answer. Re-implementing wildcard matching in this service to work around that
 * would be a second permission system, which is exactly what ADR 0003 forbids.
 * Wildcards therefore stay seed and migration data.
 */
@Service
public class PermissionGrantService {

    private final GrantWriteRepository grants;
    private final RankCatalogRepository ranks;
    private final JobFunctionCatalogRepository jobFunctions;
    private final OrgUnitCatalogRepository units;
    private final UserAccountCatalogRepository accounts;
    private final EmployeeService employeeService;
    private final PermissionEvaluator evaluator;

    public PermissionGrantService(GrantWriteRepository grants, RankCatalogRepository ranks,
            JobFunctionCatalogRepository jobFunctions, OrgUnitCatalogRepository units,
            UserAccountCatalogRepository accounts, EmployeeService employeeService, PermissionEvaluator evaluator) {
        if (grants == null) {
            throw new NullPointerException("grants");
        }
        if (ranks == null) {
            throw new NullPointerException("ranks");
        }
        if (jobFunctions == null) {
            throw new NullPointerException("jobFunctions");
        }
        if (units == null) {
            throw new NullPointerException("units");
        }
        if (accounts == null) {
            throw new NullPointerException("accounts");
        }
        if (employeeService == null) {
            throw new NullPointerException("employeeService");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.grants = grants;
        this.ranks = ranks;
        this.jobFunctions = jobFunctions;
        this.units = units;
        this.accounts = accounts;
        this.employeeService = employeeService;
        this.evaluator = evaluator;
    }

    /**
     * Everything attached to one rank / 직무 / unit / account.
     *
     * <p>The admin screen that adds a grant is the one that has to show what is
     * there already, or the second attempt arrives as a unique-constraint error
     * with no explanation.
     */
    @Transactional(readOnly = true)
    public List<PermissionGrant> list(PermissionPrincipal caller, GrantSource source, String sourceId,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        PermissionTarget reach = reachOf(source, sourceId, null, businessDate);
        evaluator.check(caller, OrgPermissions.PERMISSION_READ, reach).orThrow();

        String label = labelOf(source, sourceId);
        List<PermissionGrant> attached = new ArrayList<PermissionGrant>();
        for (PermissionGrantRow row : grants.findBySourceAndSourceId(source, sourceId)) {
            if (row.isRevoked()) {
                // The same view the evaluator has. Revoked grants are kept for
                // the audit trail, not shown as though they still applied.
                continue;
            }
            attached.add(row.toDomain(label));
        }
        return Immutables.copyOf(attached);
    }

    /**
     * Attaches a permission to a rank, a 직무, a unit or one account.
     *
     * <p>{@code allow == false} writes an explicit deny, which beats every allow
     * within its own scope and is never out-voted. It is gated exactly as an allow
     * is: taking a right away is as consequential as giving one.
     */
    @Transactional
    public PermissionGrant grant(PermissionPrincipal caller, GrantSource source, String sourceId, PermissionKey key,
            PermissionScope scope, boolean allow, String reason, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (scope == null) {
            throw new NullPointerException("scope");
        }
        if (key.isWildcard()) {
            throw new IllegalArgumentException("a wildcard permission cannot be granted through this service: "
                    + key + ". Grant the concrete resource trees instead");
        }

        PermissionTarget reach = reachOf(source, sourceId, scope, businessDate);
        evaluator.check(caller, OrgPermissions.PERMISSION_GRANT, reach).orThrow();
        // The escalation gate. Denial surfaces as the missing permission itself,
        // which is the answer the caller needs: not "you may not grant", but
        // "you do not hold what you are trying to hand out".
        evaluator.check(caller, key, reach).orThrow();

        for (PermissionGrantRow existing : grants.findBySourceAndSourceId(source, sourceId)) {
            if (existing.isRevoked()) {
                // Uniqueness applies to live grants only, so a permission that
                // was taken away can be given back. Counting the revoked row
                // here would make the revocation permanent by accident.
                continue;
            }
            if (existing.key().equals(key) && existing.scope() == scope && existing.isAllow() == allow) {
                throw new IllegalArgumentException((allow ? "allow " : "deny ") + key + " at " + scope
                        + " is already attached to " + labelOf(source, sourceId));
            }
        }

        PermissionGrantRow row = new PermissionGrantRow(UUID.randomUUID().toString(), source, sourceId, key, scope,
                allow);
        row.setGrantedBy(caller.accountId());
        row.setReason(Arguments.required(reason, "reason"));
        return grants.save(row).toDomain(labelOf(source, sourceId));
    }

    /**
     * Takes a grant back, without deleting anything.
     *
     * <p>The row is marked revoked, with who did it and why, and the evaluator
     * stops seeing it (see {@code V11__permission_grant_revocation.sql}). A
     * deleted row could not answer the question an auditor actually asks — who
     * could approve this last March, and who took that away — and it would throw
     * away the mandatory reason at the moment it becomes worth having.
     *
     * <p>Uniqueness is enforced over live grants only, so a permission that was
     * revoked can be granted again.
     */
    @Transactional
    public void revoke(PermissionPrincipal caller, String grantId, String reason, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Arguments.required(reason, "reason");

        Optional<PermissionGrantRow> found = grants.findById(Arguments.required(grantId, "grantId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("permission grant", grantId);
        }
        PermissionGrantRow row = found.get();
        if (row.isRevoked()) {
            // Two admins pressing the same button is not a failure, and the
            // first revocation's reason is the true one.
            return;
        }

        PermissionTarget reach = reachOf(row.source(), row.sourceId(), row.scope(), businessDate);
        evaluator.check(caller, OrgPermissions.PERMISSION_REVOKE, reach).orThrow();
        if (!row.key().isWildcard()) {
            // A wildcard seeded at install time cannot be run through the gate,
            // and refusing to remove it would make it permanent. Revoking one
            // needs admin.permission:revoke and nothing more, which is recorded
            // here so that the asymmetry is visible rather than accidental.
            evaluator.check(caller, row.key(), reach).orThrow();
        }
        row.revoke(caller.accountId(), reason);
        grants.save(row);
    }

    /**
     * The widest thing a grant can touch, expressed as a target the evaluator can
     * answer about.
     *
     * <p>A grant is not attached to a row, so there is no natural target for it.
     * What there is, is a reach: the rank spans a company, the unit spans its
     * subtree, {@code ALL} spans the installation. The caller is checked against
     * the reach, never against the grant.
     */
    private PermissionTarget reachOf(GrantSource source, String sourceId, PermissionScope scope,
            LocalDate businessDate) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        String id = Arguments.required(sourceId, "sourceId");
        if (scope == PermissionScope.ALL) {
            return PermissionTarget.installationWide(businessDate);
        }
        switch (source) {
            case RANK: {
                Rank rank = requireRank(id);
                return OrgTargets.company(rank.companyId(), businessDate,
                        "everyone at rank " + rank.labelKo());
            }
            case JOB_FUNCTION: {
                JobFunction jobFunction = requireJobFunction(id);
                return OrgTargets.company(jobFunction.companyId(), businessDate,
                        "everyone performing " + jobFunction.labelKo());
            }
            case ORG_UNIT: {
                OrgUnit unit = requireUnit(id);
                if (scope == PermissionScope.COMPANY) {
                    return OrgTargets.company(unit.companyId(), businessDate,
                            "company-wide grant hanging off unit " + unit.code());
                }
                return OrgTargets.unit(unit.companyId(), unit.id(), businessDate, "unit " + unit.code());
            }
            case USER_ACCOUNT: {
                return accountReach(id, scope, businessDate);
            }
            case TEMPORARY_MASTER_CAPABILITY:
            default:
                // Capabilities are ticked by the temporary-master flow in the auth
                // module, time-boxed and logged there. Issuing one from the org
                // admin screen would produce a capability with no expiry and no
                // support ticket behind it.
                throw new IllegalArgumentException("temporary master capabilities are issued by the "
                        + "temporary-master flow, not by the grant book");
        }
    }

    /** An account's reach: the person behind it, or the installation when there is none. */
    private PermissionTarget accountReach(String accountId, PermissionScope scope, LocalDate businessDate) {
        Optional<UserAccount> found = accounts.findById(accountId);
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("account", accountId);
        }
        UserAccount account = found.get();
        if (account.employeeId() == null) {
            // A service account belongs to no company, so no company-level
            // authority covers it. Installation-wide is the honest answer.
            return PermissionTarget.installationWide(businessDate);
        }
        Employee employee = employeeService.require(account.employeeId());
        if (scope == PermissionScope.COMPANY) {
            return OrgTargets.company(employee.companyId(), businessDate,
                    "company-wide grant on account " + account.username());
        }
        return employeeService.target(employee, businessDate);
    }

    /** What the explainer will call this source. Resolved once, not per row. */
    private String labelOf(GrantSource source, String sourceId) {
        switch (source) {
            case RANK:
                return requireRank(sourceId).labelKo();
            case JOB_FUNCTION:
                return requireJobFunction(sourceId).labelKo();
            case ORG_UNIT:
                return requireUnit(sourceId).nameKo();
            case USER_ACCOUNT:
                return "granted directly to this account";
            default:
                return Texts.strip(sourceId);
        }
    }

    private Rank requireRank(String rankId) {
        Optional<Rank> found = ranks.findById(rankId);
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("rank", rankId);
        }
        return found.get();
    }

    private JobFunction requireJobFunction(String jobFunctionId) {
        Optional<JobFunction> found = jobFunctions.findById(jobFunctionId);
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("job function", jobFunctionId);
        }
        return found.get();
    }

    private OrgUnit requireUnit(String orgUnitId) {
        Optional<OrgUnit> found = units.findById(orgUnitId);
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("org unit", orgUnitId);
        }
        return found.get();
    }
}
