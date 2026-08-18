package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.AttendanceInterval;
import java.io.Serializable;

/**
 * One person's line on the who's-in board.
 *
 * <h2>A hidden status is present but unreadable, not absent</h2>
 *
 * <p>When a status is not {@code visibleToPeers}, the entry still appears with
 * {@link #isVisible()} false and no details. Dropping the person from the board
 * entirely would be worse for them, not better: colleagues would read the gap as
 * "not at work today", which is a louder statement about someone on 병가 than the
 * status they were trying to keep private.
 */
public final class WhoIsInEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String employeeId;
    private final String statusCode;
    private final String labelKo;
    private final String labelEn;
    private final String colour;
    private final String icon;
    private final boolean countsAsWorking;
    private final boolean visible;
    private final AttendanceInterval interval;

    WhoIsInEntry(String employeeId, String statusCode, String labelKo, String labelEn,
            String colour, String icon, boolean countsAsWorking, boolean visible,
            AttendanceInterval interval) {
        this.employeeId = employeeId;
        this.statusCode = statusCode;
        this.labelKo = labelKo;
        this.labelEn = labelEn;
        this.colour = colour;
        this.icon = icon;
        this.countsAsWorking = countsAsWorking;
        this.visible = visible;
        this.interval = interval;
    }

    /** Somebody with no record for the day at all. */
    static WhoIsInEntry unrecorded(String employeeId) {
        return new WhoIsInEntry(employeeId, null, null, null, null, null, false, true, null);
    }

    /** Somebody whose status this viewer is not entitled to see. */
    static WhoIsInEntry hidden(String employeeId) {
        return new WhoIsInEntry(employeeId, null, "비공개", "Private", null, null, false, false,
                null);
    }

    public String employeeId() {
        return employeeId;
    }

    /** Null when hidden or unrecorded. */
    public String statusCode() {
        return statusCode;
    }

    public String labelKo() {
        return labelKo;
    }

    public String labelEn() {
        return labelEn;
    }

    public String colour() {
        return colour;
    }

    public String icon() {
        return icon;
    }

    /** Whether this status counts towards worked hours, per its configuration. */
    public boolean countsAsWorking() {
        return countsAsWorking;
    }

    /** False when the status is not {@code visibleToPeers} and the viewer is a peer. */
    public boolean isVisible() {
        return visible;
    }

    /** When it started and, if it has, when it ended. Null when hidden or unrecorded. */
    public AttendanceInterval interval() {
        return interval;
    }

    /** True when the person is in this status right now — an open interval. */
    public boolean isCurrent() {
        return interval != null && interval.isOpen();
    }
}
