package com.coreintra.app.seed;

import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.service.AttendanceRecordService;
import com.coreintra.attendance.service.AttendanceStatusService;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A month of attendance, including the night that ends at 27:00.
 *
 * <p>The midnight-crossing shift is the reason this step exists in the form it does. 노은성
 * starts at 22:00 on the 16th and clocks out at 03:00 on the 17th, and the record has to end up
 * on the 16th with an offset of 97,200 seconds — because the shift belongs to the day it began,
 * and a system that files it under the 17th has quietly lost the fact that it was one night's
 * work. It is recorded through {@code endOpenStatus} with the wall clock the button was pressed
 * at, which is the path the real clock-out takes: the service, not the caller, decides which
 * business day the moment belongs to.
 *
 * <p>Attendance is written as 인사팀 부장, not as the operator. She can do it because her 직무
 * carries {@code hr.attendance:write} company-wide, so this step is also a live test of the
 * grant book: revoke that grant and the seed stops working, which is the correct relationship
 * between a demo and the permission model.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
class AttendanceSeed {

    private final AttendanceStatusService statuses;
    private final AttendanceRecordService records;

    AttendanceSeed(AttendanceStatusService statuses, AttendanceRecordService records) {
        this.statuses = statuses;
        this.records = records;
    }

    /**
     * The six built-in statuses, for each company.
     *
     * <p>{@code V6__seed_defaults.sql} holds the same six, but it inserts them for companies
     * that existed when it ran. These companies did not, so the seed defines them with the same
     * codes, labels and behaviour flags rather than leaving the board empty.
     */
    void statuses(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        for (String companyCode : world.companyCodes()) {
            String companyId = world.companyId(companyCode);
            for (String[] row : DemoCompany.ATTENDANCE_STATUSES) {
                StatusBehaviour behaviour = StatusBehaviour.builder()
                        .countsAsWorking(Boolean.parseBoolean(row[5]))
                        .requiresApproval(Boolean.parseBoolean(row[6]))
                        .deductsLeaveBalance(Boolean.parseBoolean(row[7]))
                        .visibleToPeers(Boolean.parseBoolean(row[8]))
                        // 자리비움 clears itself: a board full of stale "away" markers is a board
                        // people stop reading.
                        .autoExpiresAfter("AWAY".equals(row[0]) ? Duration.ofHours(2) : null)
                        .build();
                statuses.define(operator, companyId, row[0], row[1], row[2], row[3], row[4],
                        behaviour, Integer.parseInt(row[9]), DemoCompany.TODAY);
            }
        }
    }

    /** @return how many attendance records were written */
    int records(SeedWorld world) {
        PermissionPrincipal hrLead = world.principal(DemoCompany.HR_LEAD_EMPLOYEE_NUMBER);
        String companyId = world.companyId(DemoCompany.HQ_CODE);
        String leaveDocumentId = world.documentId(ApprovalSeed.LEAVE_REQUEST);
        LocalDate first = DemoCompany.ATTENDANCE_MONTH.atDay(1);
        LocalDate last = DemoCompany.ATTENDANCE_MONTH.atEndOfMonth();
        int written = 0;

        for (String[] person : DemoPeople.of(DemoCompany.HQ_CODE)) {
            if (!isTracked(person)) {
                continue;
            }
            String employeeId = world.employeeId(person[DemoPeople.NUMBER]);
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                if (isWeekend(day)) {
                    continue;
                }
                written += dayOf(hrLead, companyId, employeeId, person, day, leaveDocumentId);
            }
        }
        return written;
    }

    private int dayOf(PermissionPrincipal actor, String companyId, String employeeId,
            String[] person, LocalDate day, String leaveDocumentId) {

        if (isLeaveDay(person, day)) {
            // 휴가 requires a source document, and the module refuses the record without one.
            // Naming the 휴가신청서 this day came from is what makes it defensible three months
            // later; the request itself is still one signature short, for the reason
            // ApprovalSeed explains.
            records.startStatus(actor, companyId, employeeId, "LEAVE",
                    BusinessInstant.of(day, DemoCompany.WORK_START_HOUR, 0, 0),
                    "연차 1일 — 휴가신청서 결재 진행 중", leaveDocumentId);
            records.endOpenStatusAt(actor, companyId, employeeId,
                    BusinessInstant.of(day, DemoCompany.WORK_END_HOUR, 0, 0));
            return 1;
        }

        String code = isRemoteDay(person, day) ? "REMOTE" : "WORKING";
        records.startStatus(actor, companyId, employeeId, code,
                BusinessInstant.of(day, DemoCompany.WORK_START_HOUR, 0, 0), null, null);
        records.endOpenStatusAt(actor, companyId, employeeId,
                BusinessInstant.of(day, DemoCompany.WORK_END_HOUR, 0, 0));
        int written = 1;

        if (isNightShift(person, day)) {
            records.startStatus(actor, companyId, employeeId, "WORKING",
                    BusinessInstant.of(day, DemoCompany.NIGHT_SHIFT_START_HOUR, 0, 0),
                    "릴리스 대응 야간근무", null);
            // Ended with the wall clock, on the following calendar day. The service works out
            // that the open record belongs to the previous business day and stamps 27:00 on it.
            records.endOpenStatus(actor, companyId, employeeId,
                    LocalDateTime.of(day.plusDays(1),
                            LocalTime.of(DemoCompany.NIGHT_SHIFT_END_HOUR - 24, 0)));
            written++;
        }
        return written;
    }

    /** The end of the night shift, read back so the summary can report the stored offset. */
    int nightShiftOffsetSeconds(SeedWorld world) {
        PermissionPrincipal hrLead = world.principal(DemoCompany.HR_LEAD_EMPLOYEE_NUMBER);
        String companyId = world.companyId(DemoCompany.HQ_CODE);
        String employeeId = world.employeeId(DemoCompany.NIGHT_SHIFT_EMPLOYEE_NUMBER);
        int latest = 0;
        for (AttendanceRecord record : records.dayOf(hrLead, companyId, employeeId,
                DemoCompany.NIGHT_SHIFT_DATE)) {
            BusinessInstant endedAt = record.interval().endedAt();
            if (endedAt != null && endedAt.offsetSeconds() > latest) {
                latest = endedAt.offsetSeconds();
            }
        }
        return latest;
    }

    private static boolean isTracked(String[] person) {
        for (String unitCode : DemoCompany.FULL_MONTH_ATTENDANCE_UNITS) {
            if (unitCode.equals(person[DemoPeople.UNIT])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static boolean isLeaveDay(String[] person, LocalDate day) {
        return DemoCompany.PROMOTED_EMPLOYEE_NUMBER.equals(person[DemoPeople.NUMBER])
                && day.equals(LocalDate.of(2026, 7, 24));
    }

    /** 재택 on Wednesdays, for the half of the team whose employee number is even. */
    private static boolean isRemoteDay(String[] person, LocalDate day) {
        if (day.getDayOfWeek() != DayOfWeek.WEDNESDAY) {
            return false;
        }
        String number = person[DemoPeople.NUMBER];
        return Texts.hasText(number)
                && (number.charAt(number.length() - 1) - '0') % 2 == 0;
    }

    private static boolean isNightShift(String[] person, LocalDate day) {
        return DemoCompany.NIGHT_SHIFT_EMPLOYEE_NUMBER.equals(person[DemoPeople.NUMBER])
                && day.equals(DemoCompany.NIGHT_SHIFT_DATE);
    }
}
