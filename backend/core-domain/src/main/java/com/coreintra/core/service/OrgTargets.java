package com.coreintra.core.service;

import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;

/**
 * Builds the permission targets the org services check against.
 *
 * <p>Exists so that the mapping from "a row in the org chart" to "the thing a
 * grant reaches" is written once. A target that forgets its org unit silently
 * puts every ORG_UNIT-scoped grant out of reach, and a target that forgets its
 * owner silently puts every SELF-scoped grant out of reach; both failures look
 * like a permission bug rather than a missing field, so they are worth keeping
 * out of the individual call sites.
 */
final class OrgTargets {

    private OrgTargets() {
    }

    /** A whole legal entity: reachable by COMPANY and ALL scoped grants. */
    static PermissionTarget company(String companyId, LocalDate asOf, String description) {
        return PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(asOf)
                .description(description)
                .build();
    }

    /** One unit of the tree, and the company it belongs to. */
    static PermissionTarget unit(String companyId, String orgUnitId, LocalDate asOf, String description) {
        return PermissionTarget.builder()
                .companyId(companyId)
                .orgUnitId(orgUnitId)
                .asOfBusinessDate(asOf)
                .description(description)
                .build();
    }

    /**
     * A row about one person.
     *
     * <p>{@code orgUnitId} may be null - a new hire with no position yet, or a
     * leaver whose last position has closed - and in that case ORG_UNIT scoped
     * grants correctly do not reach it: nobody manages a unit the person is not
     * in.
     */
    static PermissionTarget employee(String companyId, String orgUnitId, String employeeId,
            LocalDate asOf, String description) {
        return PermissionTarget.builder()
                .companyId(companyId)
                .orgUnitId(orgUnitId)
                .ownerEmployeeId(employeeId)
                .asOfBusinessDate(asOf)
                .description(description)
                .build();
    }
}
