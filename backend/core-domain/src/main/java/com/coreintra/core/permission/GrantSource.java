package com.coreintra.core.permission;

/**
 * Where a grant came from. Carried through to the explainer so an admin can see
 * not just that a user may do something, but which assignment gave it to them —
 * which is the difference between a fixable answer and a shrug.
 */
public enum GrantSource {

    /** Attached to a 직급. Everyone at that rank inherits it. */
    RANK,

    /** Attached to a 직무. Everyone performing that function inherits it. */
    JOB_FUNCTION,

    /** Attached to an org unit. Everyone holding a position in it inherits it. */
    ORG_UNIT,

    /** Attached to one account. The narrowest and most auditable source. */
    USER_ACCOUNT,

    /**
     * A capability ticked on a temporary master account.
     *
     * <p>Never inherited, never implied by any other source, and always
     * time-boxed. Kept distinct so the explainer and the audit log can say
     * "this happened because a support session had this capability ticked".
     */
    TEMPORARY_MASTER_CAPABILITY
}
