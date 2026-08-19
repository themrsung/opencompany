package com.coreintra.runtime.audit;

/**
 * What was done. Coarse on purpose: the fine detail is in the capability and
 * the before/after documents, and an enum that grows a value per endpoint stops
 * being a thing anyone can filter on.
 */
public enum AuditAction {

    /**
     * A read. Present because §12 asks for every sensitive read, and because a
     * support session that only looked is still a session someone must be able
     * to review.
     */
    READ,

    CREATE,

    UPDATE,

    /** 무효 - a versioned supersession. Never a hard delete; there are none. */
    VOID,

    /**
     * A read that left the building. Kept apart from {@link #READ} because
     * "opened one payslip" and "exported the payroll" are not the same event,
     * however similar the SQL looked.
     */
    EXPORT,

    LOGIN,

    LOGOUT
}
