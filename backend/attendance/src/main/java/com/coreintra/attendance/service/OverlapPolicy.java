package com.coreintra.attendance.service;

/**
 * What happens when a new status would overlap one already recorded.
 *
 * <p>Configurable rather than assumed, because reasonable installations differ
 * and each of these is somebody's correct answer. A factory where 근무 and 교육
 * genuinely run at once needs {@link #ALLOW}; an office where people forget to
 * clock out needs {@link #CLOSE_PREVIOUS}; a site billing clients by the hour
 * needs {@link #REFUSE}, because there the overlap is the error.
 */
public enum OverlapPolicy {

    /**
     * Refuse the new status and say what it clashes with.
     *
     * <p>Strictest, and right where attendance is billed: two statuses covering
     * the same minute means one of them is wrong, and guessing which would put a
     * fiction on an invoice.
     */
    REFUSE,

    /**
     * End the open status at the new one's start.
     *
     * <p>The forgiving default. Someone going from 근무 to 외근 at 14:00 means they
     * stopped being at their desk at 14:00, and making them clock out first is
     * ceremony that produces gaps when they forget.
     */
    CLOSE_PREVIOUS,

    /**
     * Let them coexist.
     *
     * <p>For statuses that genuinely stack — being on 교육 while nominally 근무.
     * Reports then have to decide which one counts, which is what
     * {@link com.coreintra.attendance.domain.StatusBehaviour#countsAsWorking()}
     * is for.
     */
    ALLOW
}
