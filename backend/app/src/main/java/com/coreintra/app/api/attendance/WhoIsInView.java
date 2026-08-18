package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.attendance.domain.AttendanceInterval;
import com.coreintra.attendance.service.WhoIsInEntry;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One person's line on the who's-in board.
 *
 * <p>§12 names this one of the two screens that decide whether people like this
 * product, and what makes it liked is that it is instantly readable: a colour, a
 * word, and whether the person is there now. Everything here serves that and
 * nothing else — the board is not a timesheet.
 *
 * <p>{@code visibleToPeers} is honoured by the service before the row is built.
 * A status a peer may not see comes back as 비공개 with {@link #isVisible()}
 * false and no interval, rather than being dropped from the board: the person is
 * on the team and their absence from the list would be read as an answer.
 * "비공개" says that there is something and you are not entitled to it, which is
 * the honest one.
 */
@Schema(name = "WhoIsInEntry", description = "One person on the who's-in board.")
public class WhoIsInView {

    private final String employeeId;
    private final String statusCode;
    private final String labelKo;
    private final String labelEn;
    private final String colour;
    private final String icon;
    private final boolean countsAsWorking;
    private final boolean visible;
    private final boolean current;
    private final String startedAt;
    private final String endedAt;
    private final String businessDate;

    private WhoIsInView(WhoIsInEntry entry) {
        AttendanceInterval interval = entry.interval();
        this.employeeId = entry.employeeId();
        this.statusCode = entry.statusCode();
        this.labelKo = entry.labelKo();
        this.labelEn = entry.labelEn();
        this.colour = entry.colour();
        this.icon = entry.icon();
        this.countsAsWorking = entry.countsAsWorking();
        this.visible = entry.isVisible();
        this.current = entry.isCurrent();
        this.startedAt = interval == null ? null : ApiWire.wire(interval.startedAt());
        this.endedAt = interval == null ? null : ApiWire.wire(interval.endedAt());
        this.businessDate = interval == null ? null : interval.businessDate().toString();
    }

    public static WhoIsInView from(WhoIsInEntry entry) {
        return new WhoIsInView(entry);
    }

    public String getEmployeeId() {
        return employeeId;
    }

    @Schema(description = "Null when this person has recorded nothing on the day being "
            + "asked about. The board shows them rather than hiding them: a blank line is "
            + "information.", example = "REMOTE")
    public String getStatusCode() {
        return statusCode;
    }

    public String getLabelKo() {
        return labelKo;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getColour() {
        return colour;
    }

    public String getIcon() {
        return icon;
    }

    public boolean isCountsAsWorking() {
        return countsAsWorking;
    }

    @Schema(description = "False when the status is not visibleToPeers and the viewer is a "
            + "peer. The row still appears, labelled 비공개, because omitting the person "
            + "entirely would itself be an answer about them.")
    public boolean isVisible() {
        return visible;
    }

    @Schema(description = "True when the spell is still open — the difference between "
            + "'재택' and '재택했음'.")
    public boolean isCurrent() {
        return current;
    }

    @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ".",
            example = "2026-08-30T09:12:00.000")
    public String getStartedAt() {
        return startedAt;
    }

    @Schema(description = "Business instant, or null while the spell is open. Reads past "
            + "24:00 for a shift that ran through midnight.")
    public String getEndedAt() {
        return endedAt;
    }

    public String getBusinessDate() {
        return businessDate;
    }
}
