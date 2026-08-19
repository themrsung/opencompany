package com.coreintra.attendance.repository;

import com.coreintra.attendance.entity.AttendanceRecord;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, String> {

    List<AttendanceRecord> findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
            String employeeId, LocalDate businessDate);

    /**
     * The who's-in view for one business day.
     *
     * <p>One of the two screens that decide whether people like this product, so
     * it is a single indexed query over the day rather than per-employee lookups.
     * Filtering by {@code visibleToPeers} happens above this, on the status type,
     * because a private status must still be visible to the person in it.
     */
    @Query("select r from AttendanceRecord r "
            + "where r.businessDate = :businessDate and r.employeeId in :employeeIds "
            + "order by r.employeeId asc, r.startedOffsetSeconds asc")
    List<AttendanceRecord> findDayForEmployees(@Param("businessDate") LocalDate businessDate,
            @Param("employeeIds") Collection<String> employeeIds);

    /** Open records, for the auto-expiry sweep and for clock-out reminders. */
    List<AttendanceRecord> findByBusinessDateAndEndedOffsetSecondsIsNull(LocalDate businessDate);
}
