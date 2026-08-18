package com.coreintra.attendance.service;

import com.coreintra.core.permission.PermissionKey;

/**
 * The permission keys this module checks.
 *
 * <h2>Self-service is not a special case</h2>
 *
 * <p>Clocking yourself in goes through the same check as clocking someone else
 * in. What differs is the <em>target</em>: it names the employee who owns the
 * record, so a {@link com.coreintra.core.permission.PermissionScope#SELF}
 * -scoped grant of {@link #ATTENDANCE_WRITE} covers your own attendance and
 * nothing else, while a team lead needs a unit-scoped one.
 *
 * <p>Special-casing "it's me, so skip the check" would put a second, invisible
 * authorisation rule beside the evaluator — and it would be the rule nobody
 * remembers to update when an installation decides that only a manager may
 * close a shift retroactively.
 */
public final class AttendancePermissions {

    /** Seeing someone's attendance records and status history. */
    public static final PermissionKey ATTENDANCE_READ =
            PermissionKey.of("hr.attendance", "read");

    /** Starting, ending or correcting an attendance record. */
    public static final PermissionKey ATTENDANCE_WRITE =
            PermissionKey.of("hr.attendance", "write");

    /** Defining the company's status types. An administrative act, not a daily one. */
    public static final PermissionKey STATUS_ADMIN =
            PermissionKey.of("hr.attendance.status", "write");

    /** Seeing a leave balance and its ledger. Yours is a SELF-scoped grant. */
    public static final PermissionKey LEAVE_READ = PermissionKey.of("hr.leave", "read");

    /**
     * Writing a leave transaction directly — a grant, an expiry, a correction.
     *
     * <p>Not what an approved 휴가 request uses. That write is made by the
     * approval itself, on the authority of the 결재 that authorised it, which is
     * why {@link LeaveService#recordApprovedLeave} takes an approval document id
     * rather than a principal with this permission.
     */
    public static final PermissionKey LEAVE_WRITE = PermissionKey.of("hr.leave", "write");

    private AttendancePermissions() {
    }
}
