package com.coreintra.core.permission;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The evaluator. Deny by default; explicit deny beats any allow within its reach.
 *
 * <h2>The algorithm, in order</h2>
 *
 * <ol>
 *   <li>Resolve the principal's org state <em>as of the target's business
 *       date</em>. Not today's — a check on a past-dated document must see the
 *       org as it was.</li>
 *   <li>Collect every grant reaching that principal, from all four sources.</li>
 *   <li>Keep the grants whose key matches the required permission (a granted
 *       wildcard may match; a required wildcard is rejected as a programming
 *       error).</li>
 *   <li>Of those, keep the ones whose scope actually reaches this target.</li>
 *   <li>If any surviving grant is a <b>deny</b>, deny. No count of allows
 *       overrides it.</li>
 *   <li>Otherwise if any surviving grant allows, allow.</li>
 *   <li>Otherwise deny, because nothing granted it.</li>
 * </ol>
 *
 * <h2>What a deny does and does not reach</h2>
 *
 * <p>A deny wins <em>within its own scope</em>. A {@code SELF}-scoped deny on
 * {@code hr.employee:read} blocks reading your own record while leaving a
 * {@code COMPANY}-scoped allow intact for everyone else's. This is deliberate:
 * the alternative — any deny killing every allow of that key — would make a
 * narrow exception unexpressible, and admins would work around it by deleting
 * the allow, losing the intent.
 *
 * <h2>Master accounts</h2>
 *
 * <p>Master status does <em>not</em> short-circuit this method. Masters hold
 * broad grants like anyone else, so their authority is visible in the explainer
 * rather than implicit in a branch here, and actions that require approval
 * still require it. A master with a grant revoked genuinely loses the ability.
 *
 * <p>Stateless and thread-safe, given thread-safe directories.
 */
public class DefaultPermissionEvaluator implements PermissionEvaluator {

    private final OrgDirectory orgDirectory;
    private final GrantDirectory grantDirectory;

    public DefaultPermissionEvaluator(OrgDirectory orgDirectory, GrantDirectory grantDirectory) {
        if (orgDirectory == null) {
            throw new NullPointerException("orgDirectory");
        }
        if (grantDirectory == null) {
            throw new NullPointerException("grantDirectory");
        }
        this.orgDirectory = orgDirectory;
        this.grantDirectory = grantDirectory;
    }

    @Override
    public PermissionDecision check(PermissionPrincipal principal, String resource, String action,
            PermissionTarget target) {
        return check(principal, PermissionKey.of(resource, action), target);
    }

    @Override
    public PermissionDecision check(PermissionPrincipal principal, PermissionKey key,
            PermissionTarget target) {
        if (principal == null) {
            throw new NullPointerException("principal");
        }
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (key.isWildcard()) {
            // Asking "may I do anything in accounting?" has no answer that is
            // safe to act on. Wildcards exist to be granted, never required.
            throw new IllegalArgumentException(
                    "the required permission must be concrete, not a wildcard: " + key);
        }

        PermissionDecision.Trace trace = new PermissionDecision.Trace();
        LocalDate asOf = target.asOfBusinessDate();
        PrincipalOrgState orgState = orgDirectory.resolve(principal, asOf);
        List<PermissionGrant> grants = grantDirectory.grantsFor(principal, orgState);

        List<PermissionGrant> applicableDenies = new ArrayList<PermissionGrant>();
        List<PermissionGrant> applicableAllows = new ArrayList<PermissionGrant>();

        for (PermissionGrant grant : grants) {
            if (!grant.key().matches(key)) {
                // Not about this permission at all; noise in the explainer.
                continue;
            }
            ScopeVerdict verdict = scopeReaches(grant, principal, orgState, target, asOf);
            if (!verdict.reaches) {
                trace.skipped(grant, verdict.reason);
                continue;
            }
            trace.applied(grant, verdict.reason);
            if (grant.isDeny()) {
                applicableDenies.add(grant);
            } else {
                applicableAllows.add(grant);
            }
        }

        if (!applicableDenies.isEmpty()) {
            PermissionGrant deciding = applicableDenies.get(0);
            String label = deciding.sourceLabel() == null ? deciding.sourceId() : deciding.sourceLabel();
            return PermissionDecision.denied(key, target, deciding,
                    "Explicitly denied by " + deciding.source() + " " + label
                            + ". An explicit deny is not overridden by any grant.",
                    trace.considerations());
        }
        if (!applicableAllows.isEmpty()) {
            return PermissionDecision.allowed(key, target, applicableAllows.get(0),
                    trace.considerations());
        }
        return PermissionDecision.denied(key, target, null,
                "No grant of " + key + " reaches this target. Access is denied by default.",
                trace.considerations());
    }

    @Override
    public EffectivePermissions effectivePermissions(PermissionPrincipal principal, LocalDate asOf) {
        if (asOf == null) {
            throw new NullPointerException(
                    "asOfBusinessDate is required; effective permissions are always as-of a date");
        }
        PrincipalOrgState orgState = orgDirectory.resolve(principal, asOf);
        return new EffectivePermissions(principal, asOf, orgState,
                grantDirectory.grantsFor(principal, orgState));
    }

    /** Whether a grant's scope actually reaches this target, and why. */
    private ScopeVerdict scopeReaches(PermissionGrant grant, PermissionPrincipal principal,
            PrincipalOrgState orgState, PermissionTarget target, LocalDate asOf) {
        switch (grant.scope()) {
            case ALL:
                return ScopeVerdict.reaches("scope ALL reaches every row in the installation");

            case COMPANY:
                if (target.companyId() == null) {
                    return ScopeVerdict.no("target names no company, so a COMPANY-scoped grant "
                            + "cannot reach it");
                }
                if (orgState.companyIds().contains(target.companyId())) {
                    return ScopeVerdict.reaches("holds a position in company " + target.companyId()
                            + " as of " + asOf);
                }
                return ScopeVerdict.no("holds no position in company " + target.companyId()
                        + " as of " + asOf);

            case ORG_UNIT_SUBTREE:
                if (target.orgUnitId() == null) {
                    return ScopeVerdict.no("target names no org unit");
                }
                for (String held : orgState.orgUnitIds()) {
                    if (orgDirectory.isInSubtree(held, target.orgUnitId(), asOf)) {
                        return ScopeVerdict.reaches("unit " + target.orgUnitId()
                                + " is within the subtree of " + held + " as of " + asOf);
                    }
                }
                return ScopeVerdict.no("unit " + target.orgUnitId()
                        + " is not within any unit held as of " + asOf);

            case ORG_UNIT:
                if (target.orgUnitId() == null) {
                    return ScopeVerdict.no("target names no org unit");
                }
                if (orgState.orgUnitIds().contains(target.orgUnitId())) {
                    return ScopeVerdict.reaches("holds a position in unit " + target.orgUnitId()
                            + " as of " + asOf);
                }
                return ScopeVerdict.no("holds no position in unit " + target.orgUnitId()
                        + " as of " + asOf + " (a parent unit would need ORG_UNIT_SUBTREE)");

            case SELF:
                String owner = target.ownerEmployeeId();
                if (owner == null) {
                    return ScopeVerdict.no("target names no owning employee, so SELF cannot reach it");
                }
                String employeeId = principal.employeeId();
                if (employeeId == null) {
                    return ScopeVerdict.no("this account has no employee, so SELF reaches nothing");
                }
                if (employeeId.equals(owner)) {
                    return ScopeVerdict.reaches("the target belongs to this account's own employee");
                }
                return ScopeVerdict.no("the target belongs to employee " + owner + ", not this account");

            default:
                // Unreachable while PermissionScope is exhaustive. Deny rather
                // than fall through: a new scope must be handled explicitly.
                return ScopeVerdict.no("unhandled scope " + grant.scope() + "; denied by default");
        }
    }

    private static final class ScopeVerdict {
        final boolean reaches;
        final String reason;

        private ScopeVerdict(boolean reaches, String reason) {
            this.reaches = reaches;
            this.reason = reason;
        }

        static ScopeVerdict reaches(String reason) {
            return new ScopeVerdict(true, reason);
        }

        static ScopeVerdict no(String reason) {
            return new ScopeVerdict(false, reason);
        }
    }
}
