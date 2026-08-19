package com.coreintra.core.service;

import com.coreintra.core.permission.PermissionKey;

/**
 * The permission vocabulary the org services check against.
 *
 * <p>Written down once, in one place, because a permission key is a public
 * contract: it is typed into the grant table by an administrator, it appears in
 * the effective-permissions explainer, and it is quoted in a support call. A
 * key invented inline at the call site is a key nobody can grant, since nothing
 * tells the administrator it exists.
 *
 * <p>The resource trees follow the ones already in use elsewhere in the system
 * ({@code hr.employee}, {@code company.settings}, {@code admin.permission}), so
 * that a wildcard grant of {@code hr.*:read} reaches the whole org chart the way
 * an administrator would expect it to, rather than reaching some of it.
 */
public final class OrgPermissions {

    private OrgPermissions() {
    }

    /** Reading a legal entity's own settings row. */
    public static final PermissionKey COMPANY_READ = PermissionKey.of("company.settings", "read");

    /** Renaming a company, or changing its registration number or base currency. */
    public static final PermissionKey COMPANY_UPDATE = PermissionKey.of("company.settings", "update");

    /**
     * Adding a legal entity to the installation.
     *
     * <p>Deliberately under {@code admin} rather than {@code company}: a
     * COMPANY-scoped grant on 본사 is authority over 본사, not authority to
     * incorporate a subsidiary beside it.
     */
    public static final PermissionKey COMPANY_CREATE = PermissionKey.of("admin.company", "create");

    public static final PermissionKey UNIT_READ = PermissionKey.of("hr.orgUnit", "read");
    public static final PermissionKey UNIT_CREATE = PermissionKey.of("hr.orgUnit", "create");
    public static final PermissionKey UNIT_UPDATE = PermissionKey.of("hr.orgUnit", "update");

    /**
     * Re-parenting a unit.
     *
     * <p>Separate from {@link #UNIT_UPDATE} because renaming 영업1팀 and moving it
     * under a different 본부 are different amounts of authority: a move silently
     * redirects every ORG_UNIT_SUBTREE grant above it.
     */
    public static final PermissionKey UNIT_MOVE = PermissionKey.of("hr.orgUnit", "move");

    public static final PermissionKey UNIT_RETIRE = PermissionKey.of("hr.orgUnit", "retire");

    public static final PermissionKey RANK_READ = PermissionKey.of("hr.rank", "read");
    public static final PermissionKey RANK_CREATE = PermissionKey.of("hr.rank", "create");
    public static final PermissionKey RANK_UPDATE = PermissionKey.of("hr.rank", "update");

    /**
     * Changing the seniority ladder as a whole.
     *
     * <p>A reorder moves everyone at once, so it is not the same permission as
     * relabelling one rung.
     */
    public static final PermissionKey RANK_REORDER = PermissionKey.of("hr.rank", "reorder");

    public static final PermissionKey RANK_RETIRE = PermissionKey.of("hr.rank", "retire");

    public static final PermissionKey JOB_FUNCTION_READ = PermissionKey.of("hr.jobFunction", "read");
    public static final PermissionKey JOB_FUNCTION_CREATE = PermissionKey.of("hr.jobFunction", "create");
    public static final PermissionKey JOB_FUNCTION_UPDATE = PermissionKey.of("hr.jobFunction", "update");
    public static final PermissionKey JOB_FUNCTION_RETIRE = PermissionKey.of("hr.jobFunction", "retire");

    /** Already in use across the system; reused here rather than invented again. */
    public static final PermissionKey EMPLOYEE_READ = PermissionKey.of("hr.employee", "read");
    public static final PermissionKey EMPLOYEE_CREATE = PermissionKey.of("hr.employee", "create");
    public static final PermissionKey EMPLOYEE_UPDATE = PermissionKey.of("hr.employee", "update");
    public static final PermissionKey EMPLOYEE_TERMINATE = PermissionKey.of("hr.employee", "terminate");

    public static final PermissionKey POSITION_READ = PermissionKey.of("hr.position", "read");
    public static final PermissionKey POSITION_ASSIGN = PermissionKey.of("hr.position", "assign");
    public static final PermissionKey POSITION_CLOSE = PermissionKey.of("hr.position", "close");

    public static final PermissionKey ACCOUNT_READ = PermissionKey.of("admin.account", "read");
    public static final PermissionKey ACCOUNT_CREATE = PermissionKey.of("admin.account", "create");
    public static final PermissionKey ACCOUNT_UPDATE = PermissionKey.of("admin.account", "update");

    /** Setting or clearing the master flag on an account. */
    public static final PermissionKey MASTER_UPDATE = PermissionKey.of("admin.master", "update");

    /** Reading someone else's grants; the same key the explainer service checks. */
    public static final PermissionKey PERMISSION_READ = PermissionKey.of("admin.permission", "read");

    public static final PermissionKey PERMISSION_GRANT = PermissionKey.of("admin.permission", "grant");
    public static final PermissionKey PERMISSION_REVOKE = PermissionKey.of("admin.permission", "revoke");
}
