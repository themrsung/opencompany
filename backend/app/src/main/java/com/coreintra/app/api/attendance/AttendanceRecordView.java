package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.attendance.domain.AttendanceInterval;
import com.coreintra.attendance.entity.AttendanceRecord;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One spell in one status, on one business day.
 *
 * <h2>27:00 is not a mistake</h2>
 *
 * <p>A shift that begins at 18:00 and ends at 03:00 belongs entirely to the
 * business day it began on, and its end is reported as {@code 27:00:00.000} on
 * that day (ADR 0002). The API does not normalise it to 03:00 on the following
 * calendar date, because doing so would move nine hours of somebody's work onto
 * a day they did not work it, split one shift across two timesheets, and pay
 * them differently. {@link #getBusinessDate()} and the offsets inside
 * {@link #getStartedAt()} and {@link #getEndedAt()} are the whole truth; the
 * clock on the wall is a rendering concern.
 */
@Schema(name = "AttendanceRecord", description = "One spell in one status.")
public class AttendanceRecordView {

    private final String id;
    private final String employeeId;
    private final String statusTypeId;
    private final String businessDate;
    private final String startedAt;
    private final String endedAt;
    private final boolean open;
    private final Long durationSeconds;
    private final String note;
    private final String sourceDocumentId;
    private final String recordedAt;

    private AttendanceRecordView(AttendanceRecord record) {
        AttendanceInterval interval = record.interval();
        this.id = record.id();
        this.employeeId = record.employeeId();
        this.statusTypeId = record.statusTypeId();
        this.businessDate = record.businessDate().toString();
        this.startedAt = ApiWire.wire(interval.startedAt());
        this.endedAt = ApiWire.wire(interval.endedAt());
        this.open = record.isOpen();
        this.durationSeconds = record.isOpen()
                ? null : Long.valueOf(interval.durationSeconds());
        this.note = record.note();
        this.sourceDocumentId = record.sourceDocumentId();
        this.recordedAt = ApiWire.utc(record.createdAt());
    }

    public static AttendanceRecordView from(AttendanceRecord record) {
        return new AttendanceRecordView(record);
    }

    public String getId() {
        return id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getStatusTypeId() {
        return statusTypeId;
    }

    @Schema(description = "The day the spell belongs to, which is the day it began — not "
            + "necessarily the calendar date it ended on.", example = "2026-08-30")
    public String getBusinessDate() {
        return businessDate;
    }

    @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ".",
            example = "2026-08-30T18:00:00.000")
    public String getStartedAt() {
        return startedAt;
    }

    @Schema(description = "Business instant on the same business day as the start. A shift "
            + "that ended at 03:00 the next morning reads 27:00:00.000 here, and that is the "
            + "correct answer rather than a rounding artefact.",
            example = ApiWire.INSTANT_EXAMPLE)
    public String getEndedAt() {
        return endedAt;
    }

    @Schema(description = "True while the spell is still running.")
    public boolean isOpen() {
        return open;
    }

    @Schema(description = "Null while the record is open. Computed from the two offsets, so "
            + "a shift across midnight is nine hours rather than minus fifteen.")
    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public String getNote() {
        return note;
    }

    @Schema(description = "The 결재 document that authorised this spell, for a status that "
            + "requires approval.")
    public String getSourceDocumentId() {
        return sourceDocumentId;
    }

    public String getRecordedAt() {
        return recordedAt;
    }
}
