package com.coreintra.core.permission;

/**
 * How wide a grant reaches, relative to where the principal holds the position
 * the grant came from.
 *
 * <p>A grant of {@code hr.employee:read} at {@link #ORG_UNIT} attached to the
 * rank 부장 does not mean "부장s can read every employee". It means each 부장
 * can read the employees of the org unit where they hold that rank. Scope is
 * always resolved against the principal's own position, as of the target's
 * business date.
 *
 * <p>Declared narrowest first. {@link #covers} uses that ordering, so adding a
 * scope in the wrong place changes behaviour — keep the order.
 */
public enum PermissionScope {

    /** Only rows the principal owns — their own leave request, their own profile. */
    SELF,

    /** Rows belonging to an org unit the principal holds a position in. Not its children. */
    ORG_UNIT,

    /** That org unit and everything beneath it. */
    ORG_UNIT_SUBTREE,

    /** Every row in a company the principal holds a position in. */
    COMPANY,

    /** Every row in the installation, across companies. */
    ALL;

    /** True when this scope is at least as wide as {@code other}. */
    public boolean covers(PermissionScope other) {
        return ordinal() >= other.ordinal();
    }

    public boolean isWiderThan(PermissionScope other) {
        return ordinal() > other.ordinal();
    }
}
