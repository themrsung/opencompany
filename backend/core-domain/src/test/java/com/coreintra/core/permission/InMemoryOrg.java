package com.coreintra.core.permission;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small org chart and grant book, in memory, with effective dating.
 *
 * <p>Exists so the evaluator's rules can be tested as rules — without a
 * database, and with the as-of behaviour visible in the test rather than buried
 * in fixture SQL.
 */
final class InMemoryOrg implements OrgDirectory, GrantDirectory {

    /** unit id -> parent unit id (null for a company root). */
    private final Map<String, String> parents = new HashMap<String, String>();
    /** unit id -> company id. */
    private final Map<String, String> unitCompany = new HashMap<String, String>();

    private final List<Assignment> assignments = new ArrayList<Assignment>();
    private final List<ScopedGrant> grants = new ArrayList<ScopedGrant>();

    InMemoryOrg unit(String id, String parentId, String companyId) {
        parents.put(id, parentId);
        unitCompany.put(id, companyId);
        return this;
    }

    /** A position held from {@code from} until {@code to} (exclusive; null = open). */
    InMemoryOrg position(String accountId, String employeeId, String unitId, String rankId,
            String jobFunctionId, LocalDate from, LocalDate to) {
        assignments.add(new Assignment(accountId, employeeId, unitId, rankId, jobFunctionId, from, to));
        return this;
    }

    InMemoryOrg grantToRank(String rankId, String label, String permission, PermissionScope scope) {
        grants.add(new ScopedGrant(GrantSource.RANK, rankId, label,
                PermissionGrant.allow(PermissionKey.parse(permission), scope, GrantSource.RANK, rankId, label)));
        return this;
    }

    InMemoryOrg denyToRank(String rankId, String label, String permission, PermissionScope scope) {
        grants.add(new ScopedGrant(GrantSource.RANK, rankId, label,
                PermissionGrant.deny(PermissionKey.parse(permission), scope, GrantSource.RANK, rankId, label)));
        return this;
    }

    InMemoryOrg grantToJobFunction(String functionId, String label, String permission, PermissionScope scope) {
        grants.add(new ScopedGrant(GrantSource.JOB_FUNCTION, functionId, label,
                PermissionGrant.allow(PermissionKey.parse(permission), scope, GrantSource.JOB_FUNCTION,
                        functionId, label)));
        return this;
    }

    InMemoryOrg grantToUnit(String unitId, String label, String permission, PermissionScope scope) {
        grants.add(new ScopedGrant(GrantSource.ORG_UNIT, unitId, label,
                PermissionGrant.allow(PermissionKey.parse(permission), scope, GrantSource.ORG_UNIT,
                        unitId, label)));
        return this;
    }

    InMemoryOrg grantToAccount(String accountId, String label, String permission, PermissionScope scope) {
        grants.add(new ScopedGrant(GrantSource.USER_ACCOUNT, accountId, label,
                PermissionGrant.allow(PermissionKey.parse(permission), scope, GrantSource.USER_ACCOUNT,
                        accountId, label)));
        return this;
    }

    InMemoryOrg denyToAccount(String accountId, String label, String permission, PermissionScope scope) {
        grants.add(new ScopedGrant(GrantSource.USER_ACCOUNT, accountId, label,
                PermissionGrant.deny(PermissionKey.parse(permission), scope, GrantSource.USER_ACCOUNT,
                        accountId, label)));
        return this;
    }

    @Override
    public PrincipalOrgState resolve(PermissionPrincipal principal, LocalDate asOf) {
        Set<String> units = new HashSet<String>();
        Set<String> companies = new HashSet<String>();
        Set<String> ranks = new HashSet<String>();
        Set<String> functions = new HashSet<String>();
        String employeeId = principal.employeeId();

        for (Assignment assignment : assignments) {
            if (!assignment.accountId.equals(principal.accountId()) || !assignment.activeOn(asOf)) {
                continue;
            }
            units.add(assignment.unitId);
            String company = unitCompany.get(assignment.unitId);
            if (company != null) {
                companies.add(company);
            }
            if (assignment.rankId != null) {
                ranks.add(assignment.rankId);
            }
            if (assignment.jobFunctionId != null) {
                functions.add(assignment.jobFunctionId);
            }
            employeeId = assignment.employeeId;
        }
        return new PrincipalOrgState(employeeId, units, companies, ranks, functions);
    }

    @Override
    public boolean isInSubtree(String ancestorId, String candidateId, LocalDate asOf) {
        String cursor = candidateId;
        // Bounded so a cycle introduced by bad data cannot hang a request.
        for (int depth = 0; cursor != null && depth < 64; depth++) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            cursor = parents.get(cursor);
        }
        return false;
    }

    @Override
    public List<PermissionGrant> grantsFor(PermissionPrincipal principal, PrincipalOrgState orgState) {
        List<PermissionGrant> reaching = new ArrayList<PermissionGrant>();
        for (ScopedGrant scoped : grants) {
            boolean applies;
            switch (scoped.source) {
                case RANK:
                    applies = orgState.rankIds().contains(scoped.sourceId);
                    break;
                case JOB_FUNCTION:
                    applies = orgState.jobFunctionIds().contains(scoped.sourceId);
                    break;
                case ORG_UNIT:
                    applies = orgState.orgUnitIds().contains(scoped.sourceId);
                    break;
                case USER_ACCOUNT:
                    applies = scoped.sourceId.equals(principal.accountId());
                    break;
                default:
                    applies = false;
            }
            if (applies) {
                reaching.add(scoped.grant);
            }
        }
        return reaching;
    }

    private static final class Assignment {
        final String accountId;
        final String employeeId;
        final String unitId;
        final String rankId;
        final String jobFunctionId;
        final LocalDate from;
        final LocalDate to;

        Assignment(String accountId, String employeeId, String unitId, String rankId,
                String jobFunctionId, LocalDate from, LocalDate to) {
            this.accountId = accountId;
            this.employeeId = employeeId;
            this.unitId = unitId;
            this.rankId = rankId;
            this.jobFunctionId = jobFunctionId;
            this.from = from;
            this.to = to;
        }

        boolean activeOn(LocalDate date) {
            if (from != null && date.isBefore(from)) {
                return false;
            }
            return to == null || date.isBefore(to);
        }
    }

    private static final class ScopedGrant {
        final GrantSource source;
        final String sourceId;
        final String label;
        final PermissionGrant grant;

        ScopedGrant(GrantSource source, String sourceId, String label, PermissionGrant grant) {
            this.source = source;
            this.sourceId = sourceId;
            this.label = label;
            this.grant = grant;
        }
    }
}
