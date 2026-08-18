package com.coreintra.approval.service;

import java.time.LocalDate;

/**
 * Whether someone was away on a business date.
 *
 * <p>Exists so 대결 can be checked rather than merely asserted. Acting for an
 * approver who is at their desk is not 대결; it is someone else signing in their
 * name, and the two must not look the same in the trail.
 *
 * <h2>Optional by design</h2>
 *
 * <p>Injected as a list, so an installation that has wired no source of absence
 * gets an empty one and 대결 falls back to the mandatory written reason. That is
 * a deliberate degradation rather than an oversight: refusing every 대결 because
 * nothing can confirm the absence would break a legitimate everyday action, and
 * inventing an absence would be worse.
 *
 * <p>The implementation belongs to whichever module knows about attendance.
 * This one deliberately does not: approval has no business reading timesheets,
 * and attendance has no business knowing what a 결재선 is.
 */
public interface AbsenceDirectory {

    /**
     * True when this account was recorded absent on that date.
     *
     * <p>"Absent" means a status the client configured as not
     * {@code countsAsWorking} — 휴가, 병가, 출장 — as decided by the installation's
     * own status rows, never by a list of codes hard-coded here.
     */
    boolean isAbsent(String accountId, LocalDate businessDate);
}
