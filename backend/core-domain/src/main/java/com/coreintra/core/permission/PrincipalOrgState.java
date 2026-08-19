package com.coreintra.core.permission;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.Set;

/**
 * Where a principal stood in the organisation on one particular business date.
 *
 * <p>Resolved as-of, never "now". A check against a document dated last March
 * asks what units and companies this person held a position in last March, so
 * that a promotion since then neither grants nor removes authority over
 * history.
 */
public final class PrincipalOrgState implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String employeeId;
    private final Set<String> orgUnitIds;
    private final Set<String> companyIds;
    private final Set<String> rankIds;
    private final Set<String> jobFunctionIds;

    public PrincipalOrgState(String employeeId, Set<String> orgUnitIds, Set<String> companyIds,
            Set<String> rankIds, Set<String> jobFunctionIds) {
        this.employeeId = employeeId;
        this.orgUnitIds = safe(orgUnitIds);
        this.companyIds = safe(companyIds);
        this.rankIds = safe(rankIds);
        this.jobFunctionIds = safe(jobFunctionIds);
    }

    private static Set<String> safe(Set<String> values) {
        return values == null ? Immutables.<String>setOf() : Immutables.setCopyOf(values);
    }

    /** An account with no position at all — a service account, or a leaver. */
    public static PrincipalOrgState none(String employeeId) {
        return new PrincipalOrgState(employeeId, null, null, null, null);
    }

    public String employeeId() {
        return employeeId;
    }

    /** Units where the principal directly held a position on the date. */
    public Set<String> orgUnitIds() {
        return orgUnitIds;
    }

    public Set<String> companyIds() {
        return companyIds;
    }

    public Set<String> rankIds() {
        return rankIds;
    }

    public Set<String> jobFunctionIds() {
        return jobFunctionIds;
    }

    public boolean hasAnyPosition() {
        return !orgUnitIds.isEmpty();
    }

    @Override
    public String toString() {
        return "orgState[employee=" + employeeId + " units=" + orgUnitIds
                + " companies=" + companyIds + " ranks=" + rankIds + " functions=" + jobFunctionIds + "]";
    }
}
