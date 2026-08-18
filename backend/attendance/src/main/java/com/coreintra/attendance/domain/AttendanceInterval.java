package com.coreintra.attendance.domain;

import com.coreintra.businesstime.BusinessInstant;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * A period spent in one status, stamped in business time.
 *
 * <p>This is where the 72-hour business day earns its keep. A shift from 22:00
 * to 03:00 is one interval on one business day — {@code 22:00} to {@code 27:00}
 * — not two fragments split across a calendar boundary. Nobody has to reassemble
 * it to answer "how long did they work on the 30th?".
 *
 * <p>Both ends therefore share a business date. An interval whose start and end
 * are on different business dates is a modelling error, not a long shift: the
 * offset range already spans 72 hours, which is longer than any shift.
 */
public final class AttendanceInterval implements Serializable {

    private static final long serialVersionUID = 1L;

    private final BusinessInstant startedAt;
    private final BusinessInstant endedAt;

    private AttendanceInterval(BusinessInstant startedAt, BusinessInstant endedAt) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    /** An interval that is still running. */
    public static AttendanceInterval open(BusinessInstant startedAt) {
        if (startedAt == null) {
            throw new NullPointerException("startedAt");
        }
        return new AttendanceInterval(startedAt, null);
    }

    /**
     * @throws IllegalArgumentException if the end precedes the start, or the two
     *         ends fall on different business dates
     */
    public static AttendanceInterval closed(BusinessInstant startedAt, BusinessInstant endedAt) {
        if (startedAt == null) {
            throw new NullPointerException("startedAt");
        }
        if (endedAt == null) {
            throw new NullPointerException("endedAt");
        }
        if (!startedAt.businessDate().equals(endedAt.businessDate())) {
            throw new IllegalArgumentException(
                    "an attendance interval belongs to one business day. " + startedAt + " to "
                            + endedAt + " spans two. A shift crossing midnight stays on its own "
                            + "business date using an offset past 24:00 — 22:00 to 03:00 is "
                            + "22:00:00 to 27:00:00, not two days.");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "an interval cannot end (" + endedAt + ") before it starts (" + startedAt + ")");
        }
        return new AttendanceInterval(startedAt, endedAt);
    }

    public BusinessInstant startedAt() {
        return startedAt;
    }

    /** Null while the interval is still running. */
    public BusinessInstant endedAt() {
        return endedAt;
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public LocalDate businessDate() {
        return startedAt.businessDate();
    }

    /** Closes an open interval. */
    public AttendanceInterval closeAt(BusinessInstant end) {
        if (!isOpen()) {
            throw new IllegalStateException("this interval already ended at " + endedAt);
        }
        return closed(startedAt, end);
    }

    /** Duration in seconds, or -1 while still open. */
    public long durationSeconds() {
        if (isOpen()) {
            return -1L;
        }
        return (long) endedAt.offsetSeconds() - (long) startedAt.offsetSeconds();
    }

    /**
     * True when this and {@code other} overlap.
     *
     * <p>Touching is not overlapping: an interval ending at 18:00 and one
     * starting at 18:00 are adjacent, and treating that as a clash would reject
     * every ordinary handover.
     *
     * <p>An open interval is treated as extending to the end of its business
     * day, so a forgotten clock-out still conflicts with a later status rather
     * than silently coexisting with it.
     */
    public boolean overlaps(AttendanceInterval other) {
        if (!businessDate().equals(other.businessDate())) {
            return false;
        }
        int thisStart = startedAt.offsetSeconds();
        int thisEnd = isOpen() ? BusinessInstant.MAX_OFFSET_SECONDS : endedAt.offsetSeconds();
        int otherStart = other.startedAt.offsetSeconds();
        int otherEnd = other.isOpen()
                ? BusinessInstant.MAX_OFFSET_SECONDS
                : other.endedAt.offsetSeconds();
        return thisStart < otherEnd && otherStart < thisEnd;
    }

    @Override
    public String toString() {
        return startedAt + " → " + (isOpen() ? "(open)" : endedAt);
    }
}
