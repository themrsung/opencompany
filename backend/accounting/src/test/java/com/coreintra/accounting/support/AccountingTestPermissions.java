package com.coreintra.accounting.support;

import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.permission.PrincipalOrgState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real evaluator over an in-memory grant table, remembering every question it was asked.
 *
 * <p>It wraps {@link DefaultPermissionEvaluator} rather than faking a decision, for the reason the
 * attendance tests give: a stub that returns "allowed" tests nothing about whether the module asks
 * the right question, and a second decision engine in test scope would drift from the real one.
 * What is faked is the org chart and the grant table, which is data.
 *
 * <p>The recording half is what the accounting tests actually assert on. "Every mutating method is
 * gated" is a claim about which permission was checked, against which company, <em>as of which
 * business date</em> — and the last of those is the one that silently regresses, because passing
 * today's date instead of the entry's produces a system that works perfectly until somebody is
 * promoted.
 *
 * <p>Test scope only. The ArchUnit rule that forbids a second {@link PermissionEvaluator}
 * implementation imports production classes only, and says in its own reason why that is the right
 * boundary.
 */
public final class AccountingTestPermissions implements PermissionEvaluator {

    /** One question, as it was asked. */
    public static final class Check {
        private final PermissionKey key;
        private final PermissionTarget target;
        private final boolean allowed;

        Check(PermissionKey key, PermissionTarget target, boolean allowed) {
            this.key = key;
            this.target = target;
            this.allowed = allowed;
        }

        public PermissionKey key() {
            return key;
        }

        public PermissionTarget target() {
            return target;
        }

        public boolean allowed() {
            return allowed;
        }

        /** The date the org state was resolved as of — the thing most worth asserting on. */
        public LocalDate asOf() {
            return target.asOfBusinessDate();
        }

        public String companyId() {
            return target.companyId();
        }

        @Override
        public String toString() {
            return (allowed ? "ALLOW " : "DENY  ") + key + " on " + target;
        }
    }

    private final Grants grants = new Grants();
    private final PermissionEvaluator delegate =
            new DefaultPermissionEvaluator(new NoPositions(), grants);
    private final List<Check> checks = new ArrayList<Check>();

    /** Grants {@code accounting.*:*} at ALL scope: the caller in tests that are not about denial. */
    public PermissionPrincipal accountantWhoMayDoEverything(String accountId) {
        PermissionPrincipal principal = PermissionPrincipal.user(accountId, accountId, null);
        grants.allow(principal, PermissionKey.of("accounting.*", "*"), PermissionScope.ALL);
        return principal;
    }

    /**
     * The same, minus one permission, by an explicit deny.
     *
     * <p>An explicit deny rather than a narrower set of allows, because that is how a real
     * installation expresses "everything except this" and because deny-wins is the rule the
     * refusal has to travel through.
     */
    public PermissionPrincipal accountantWithout(String accountId, PermissionKey withheld) {
        PermissionPrincipal principal = accountantWhoMayDoEverything(accountId);
        grants.deny(principal, withheld, PermissionScope.ALL);
        return principal;
    }

    /** An account with no accounting grants at all. Deny by default does the rest. */
    public PermissionPrincipal strangerWithNoGrants(String accountId) {
        return PermissionPrincipal.user(accountId, accountId, null);
    }

    public void allow(PermissionPrincipal principal, PermissionKey key, PermissionScope scope) {
        grants.allow(principal, key, scope);
    }

    public List<Check> checks() {
        return Immutables.copyOf(checks);
    }

    public void forget() {
        checks.clear();
    }

    /** The most recent check for this permission, or null when it was never asked. */
    public Check lastCheckOf(PermissionKey key) {
        Check found = null;
        for (Check check : checks) {
            if (check.key().equals(key)) {
                found = check;
            }
        }
        return found;
    }

    /** Every check for this permission, oldest first. */
    public List<Check> checksOf(PermissionKey key) {
        List<Check> found = new ArrayList<Check>();
        for (Check check : checks) {
            if (check.key().equals(key)) {
                found.add(check);
            }
        }
        return found;
    }

    public boolean wasChecked(PermissionKey key) {
        return lastCheckOf(key) != null;
    }

    @Override
    public PermissionDecision check(PermissionPrincipal principal, PermissionKey key,
            PermissionTarget target) {
        PermissionDecision decision = delegate.check(principal, key, target);
        checks.add(new Check(key, target, decision.isAllowed()));
        return decision;
    }

    @Override
    public PermissionDecision check(PermissionPrincipal principal, String resource, String action,
            PermissionTarget target) {
        return check(principal, PermissionKey.of(resource, action), target);
    }

    @Override
    public EffectivePermissions effectivePermissions(PermissionPrincipal principal,
            LocalDate asOfBusinessDate) {
        return delegate.effectivePermissions(principal, asOfBusinessDate);
    }

    /** Grants held directly on the account, which is all these tests need. */
    private static final class Grants implements GrantDirectory {

        private final Map<String, List<PermissionGrant>> byAccount =
                new LinkedHashMap<String, List<PermissionGrant>>();

        void allow(PermissionPrincipal principal, PermissionKey key, PermissionScope scope) {
            held(principal).add(PermissionGrant.allow(key, scope, GrantSource.USER_ACCOUNT,
                    principal.accountId(), "granted directly in a test"));
        }

        void deny(PermissionPrincipal principal, PermissionKey key, PermissionScope scope) {
            held(principal).add(PermissionGrant.deny(key, scope, GrantSource.USER_ACCOUNT,
                    principal.accountId(), "denied directly in a test"));
        }

        private List<PermissionGrant> held(PermissionPrincipal principal) {
            List<PermissionGrant> list = byAccount.get(principal.accountId());
            if (list == null) {
                list = new ArrayList<PermissionGrant>();
                byAccount.put(principal.accountId(), list);
            }
            return list;
        }

        @Override
        public List<PermissionGrant> grantsFor(PermissionPrincipal principal,
                PrincipalOrgState orgState) {
            List<PermissionGrant> held = byAccount.get(principal.accountId());
            return held == null ? Immutables.<PermissionGrant>listOf() : held;
        }
    }

    /**
     * Nobody holds a position.
     *
     * <p>Accounting callers in these tests are service accounts, and the grants above are all at
     * ALL scope, so the org chart contributes nothing. Faking positions as well would be faking
     * the part of the evaluator these tests are not asking about.
     */
    private static final class NoPositions implements OrgDirectory {

        @Override
        public PrincipalOrgState resolve(PermissionPrincipal principal, LocalDate asOf) {
            return PrincipalOrgState.none(principal.employeeId());
        }

        @Override
        public boolean isInSubtree(String ancestorId, String candidateDescendantId,
                LocalDate asOf) {
            return false;
        }
    }
}
