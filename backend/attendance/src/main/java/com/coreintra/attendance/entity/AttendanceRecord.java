package com.coreintra.attendance.entity;

import com.coreintra.attendance.domain.AttendanceInterval;
import com.coreintra.businesstime.BusinessInstant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A period an employee spent in one status.
 *
 * <p>Stored as one business date plus two offsets rather than two full
 * instants, because both ends belong to the same business day by construction —
 * that is what makes a midnight-crossing shift one row instead of two.
 */
@Entity
@Table(name = "attendance_record")
public class AttendanceRecord {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "employee_id", nullable = false, length = 36)
    private String employeeId;

    @Column(name = "status_type_id", nullable = false, length = 36)
    private String statusTypeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "started_offset_seconds", nullable = false)
    private int startedOffsetSeconds;

    /** Null while the status is still current. */
    @Column(name = "ended_offset_seconds")
    private Integer endedOffsetSeconds;

    @Column(name = "note")
    private String note;

    /** The 결재 document that authorised this, where the status required one. */
    @Column(name = "source_document_id", length = 36)
    private String sourceDocumentId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AttendanceRecord() {
    }

    public AttendanceRecord(String id, String employeeId, String statusTypeId,
            BusinessInstant startedAt) {
        this.id = id;
        this.employeeId = employeeId;
        this.statusTypeId = statusTypeId;
        this.businessDate = startedAt.businessDate();
        this.startedOffsetSeconds = startedAt.offsetSeconds();
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Closes the record.
     *
     * @throws IllegalArgumentException if the end is on a different business
     *         date — a shift past midnight uses an offset above 24:00, not the
     *         next day's date
     */
    public void endAt(BusinessInstant endedAt) {
        if (!endedAt.businessDate().equals(businessDate)) {
            throw new IllegalArgumentException(
                    "an attendance record belongs to one business day. It began on " + businessDate
                            + "; ending it on " + endedAt.businessDate() + " would split it. A "
                            + "shift past midnight ends at an offset above 24:00 on the same "
                            + "business date.");
        }
        if (endedAt.offsetSeconds() < startedOffsetSeconds) {
            throw new IllegalArgumentException("a record cannot end before it started");
        }
        this.endedOffsetSeconds = Integer.valueOf(endedAt.offsetSeconds());
    }

    public AttendanceInterval interval() {
        BusinessInstant start = BusinessInstant.of(businessDate, startedOffsetSeconds);
        return endedOffsetSeconds == null
                ? AttendanceInterval.open(start)
                : AttendanceInterval.closed(start,
                        BusinessInstant.of(businessDate, endedOffsetSeconds.intValue()));
    }

    public boolean isOpen() {
        return endedOffsetSeconds == null;
    }

    public String id() {
        return id;
    }

    public String employeeId() {
        return employeeId;
    }

    public String statusTypeId() {
        return statusTypeId;
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    public String note() {
        return note;
    }

    public void setNote(String value) {
        this.note = value;
    }

    public String sourceDocumentId() {
        return sourceDocumentId;
    }

    public void setSourceDocumentId(String value) {
        this.sourceDocumentId = value;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
