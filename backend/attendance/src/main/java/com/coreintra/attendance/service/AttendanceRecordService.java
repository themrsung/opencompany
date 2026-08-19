package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.AttendanceInterval;
import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.attendance.repository.AttendanceRecordRepository;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting and ending an attendance status.
 *
 * <h2>A shift that crosses midnight stays on one business day</h2>
 *
 * <p>This is the most important behaviour in the module. Someone who starts at
 * 22:00 on the 30th and finishes at 03:00 has worked <em>one</em> shift on the
 * 30th, from {@code 22:00} to {@code 27:00}. Not two fragments either side of a
 * calendar boundary; not five hours on the 31st; not a five-hour shift that
 * reports as minus nineteen. {@link #endOpenStatus} takes the wall-clock moment
 * the person actually finished and stamps it as an offset from <em>the record's
 * own business date</em>, which is what produces the 27:00.
 *
 * <p>The alternative — recording 03:00 on the 31st — is the bug the whole
 * business-time model exists to prevent. It splits a night shift across two
 * rows, makes "how long did they work on the 30th?" a question requiring
 * reassembly, and puts the second half of Friday night's work into Saturday's
 * overtime.
 *
 * <h2>Overlap</h2>
 *
 * <p>Configured, never assumed: see {@link OverlapPolicy}. The default an
 * installation chooses is read from {@link AttendanceSettings}, so nothing here
 * decides on a client's behalf what two simultaneous statuses mean.
 */
@Service
public class AttendanceRecordService {

    /** Refusing a record that would misstate what happened. */
    public static class AttendanceRefusedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public AttendanceRefusedException(String message) {
            super(message);
        }
    }

    private final AttendanceRecordRepository records;
    private final AttendanceStatusService statuses;
    private final AttendanceSettings settings;
    private final PermissionEvaluator permissions;
    private final EmployeeTargets targets;

    public AttendanceRecordService(AttendanceRecordRepository records,
            AttendanceStatusService statuses, AttendanceSettings settings,
            PermissionEvaluator permissions, EmployeeTargets targets) {
        this.records = records;
        this.statuses = statuses;
        this.settings = settings;
        this.permissions = permissions;
        this.targets = targets;
    }

    /**
     * Starts a status.
     *
     * @param at the business instant it began — an offset past 24:00 is ordinary
     *        and means "later on the same business day"
     * @param sourceDocumentId the 결재 that authorised it, required for any status
     *        whose {@link StatusBehaviour#requiresApproval()} is set
     */
    @Transactional
    public AttendanceRecord startStatus(PermissionPrincipal actor, String companyId,
            String employeeId, String statusCode, BusinessInstant at, String note,
            String sourceDocumentId) {

        authoriseWrite(actor, companyId, employeeId, at.businessDate(),
                "starting status " + statusCode);
        AttendanceStatusType status = statuses.require(companyId, statusCode);
        StatusBehaviour behaviour = status.behaviour();

        if (behaviour.requiresApproval() && Texts.isBlank(sourceDocumentId)) {
            throw new AttendanceRefusedException(
                    status.labelKo() + " 상태는 결재된 문서가 있어야 등록할 수 있습니다. (Status \""
                            + status.code() + "\" is configured to require approval, so it can "
                            + "only be recorded against an approved 결재 document. Recording it "
                            + "without one would spend "
                            + (behaviour.deductsLeaveBalance() ? "leave " : "")
                            + "on nobody's authority.)");
        }

        List<AttendanceRecord> day = records
                .findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
                        employeeId, at.businessDate());
        applyOverlapPolicy(companyId, day, at);

        AttendanceRecord record = new AttendanceRecord(UUID.randomUUID().toString(), employeeId,
                status.id(), at);
        record.setNote(note);
        record.setSourceDocumentId(sourceDocumentId);
        return records.save(record);
    }

    /**
     * Ends the open status, given the wall-clock moment it actually finished.
     *
     * <p>The moment is a {@link LocalDateTime} rather than a
     * {@link BusinessInstant} on purpose: this is the call a clock-out button
     * makes, and the button knows what the clock on the wall says, not which
     * business day the shift belongs to. Working that out is this method's job,
     * and getting it wrong is the failure the module is built to avoid.
     *
     * <p>A shift opened at 22:00 on the 30th and ended at 03:00 on the 31st is
     * stamped {@code 2026-08-30T27:00:00.000}. The wall clock still reads 03:00
     * on the 31st — {@link BusinessInstant#absoluteDateTime()} says so — but the
     * shift belongs to the 30th and every report reads it that way.
     *
     * @throws AttendanceRefusedException if nothing is open, or if so much time
     *         has passed that the offset falls outside the 72-hour window; a
     *         shift somebody forgot to close three days ago needs a correction,
     *         not a plausible-looking guess
     */
    @Transactional
    public AttendanceRecord endOpenStatus(PermissionPrincipal actor, String companyId,
            String employeeId, LocalDateTime wallClock) {

        // Authorised before anything is looked up: the refusal below says
        // whether an employee has an open record and on which day, and that is
        // not something an unauthorised caller should be able to learn.
        authoriseWrite(actor, companyId, employeeId, wallClock.toLocalDate(),
                "ending an attendance record");
        AttendanceRecord open = requireOpenRecord(employeeId, wallClock);
        if (!open.businessDate().equals(wallClock.toLocalDate())) {
            // The record turned out to belong to the previous business day — a
            // night shift. Authorise again against the day it really belongs to.
            authoriseWrite(actor, companyId, employeeId, open.businessDate(),
                    "ending an attendance record");
        }
        open.endAt(stampOn(open.businessDate(), wallClock));
        return records.save(open);
    }

    /**
     * Ends the open status at an instant the caller has already worked out.
     *
     * <p>For corrections and for imports, where the business date is known and
     * the wall clock is not the authority.
     */
    @Transactional
    public AttendanceRecord endOpenStatusAt(PermissionPrincipal actor, String companyId,
            String employeeId, BusinessInstant at) {

        authoriseWrite(actor, companyId, employeeId, at.businessDate(),
                "ending an attendance record");
        Optional<AttendanceRecord> open = openIn(records
                .findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
                        employeeId, at.businessDate()));
        if (!open.isPresent()) {
            throw new AttendanceRefusedException(
                    "진행 중인 근태 기록이 없습니다. (No open attendance record for employee " + employeeId
                            + " on business date " + at.businessDate() + ".)");
        }
        open.get().endAt(at);
        return records.save(open.get());
    }

    /**
     * Turns a wall-clock moment into an offset on a business date.
     *
     * <p>Public and static because it is the rule, and because it is the thing
     * worth testing directly: 03:00 the morning after the 30th is
     * {@code 27:00:00} on the 30th, and 08:00 two hours before the 30th opens is
     * {@code -02:00:00} on the 30th.
     *
     * @throws AttendanceRefusedException if the moment falls outside the
     *         business day's 72-hour window
     */
    public static BusinessInstant stampOn(LocalDate businessDate, LocalDateTime wallClock) {
        long offsetSeconds = Duration.between(businessDate.atStartOfDay(), wallClock).getSeconds();
        if (offsetSeconds < BusinessInstant.MIN_OFFSET_SECONDS
                || offsetSeconds > BusinessInstant.MAX_OFFSET_SECONDS) {
            throw new AttendanceRefusedException(
                    wallClock + " is " + (offsetSeconds / 3600) + " hours from the start of "
                            + businessDate + ", outside the business day's window. A record left "
                            + "open this long needs correcting with the time it really ended, not "
                            + "closing at a moment that would be a guess.");
        }
        return BusinessInstant.of(businessDate, (int) offsetSeconds);
    }

    /** One employee's day, in start order. */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> dayOf(PermissionPrincipal actor, String companyId,
            String employeeId, LocalDate businessDate) {
        permissions.check(actor, AttendancePermissions.ATTENDANCE_READ,
                targets.of(companyId, employeeId, businessDate,
                        "attendance of " + employeeId + " on " + businessDate)).orThrow();
        return records.findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
                employeeId, businessDate);
    }

    /**
     * Closes statuses that have outstayed their configured expiry.
     *
     * <p>자리비움 is the reason this exists: a board full of stale "away" markers
     * is a board people stop reading, and a status nobody trusts is worse than
     * no status. Only statuses the client configured to expire are touched.
     *
     * <h2>It runs as somebody</h2>
     *
     * <p>The scheduled job that calls this authenticates as a named service
     * account and is authorised like anyone else. There is no privileged
     * internal caller in this system, and a sweep that closed records without
     * one would be exactly that (ADR 0003).
     *
     * <h2>Two business days, not one</h2>
     *
     * <p>Yesterday's open records are swept as well as today's, because that is
     * where an overnight status lives. Sweeping only today would leave a 자리비움
     * opened at 23:00 open forever — the same trap {@link #endOpenStatus} avoids.
     *
     * <h2>Cost, and the query that is missing</h2>
     *
     * <p>Two queries for the open records plus one for the company's status
     * types. The open-record query has no company predicate to offer — there is
     * no {@code findByCompanyIdAndBusinessDateAnd…} on the repository — so this
     * reads other tenants' open rows and discards them below. That filtering is
     * explicit rather than incidental, but the query belongs in the repository.
     *
     * @return the records it closed
     */
    @Transactional
    public List<AttendanceRecord> sweepExpired(PermissionPrincipal actor, String companyId,
            BusinessInstant now) {

        permissions.check(actor, AttendancePermissions.ATTENDANCE_WRITE,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(now.businessDate())
                        .description("expiring stale attendance statuses")
                        .build()).orThrow();

        Map<String, AttendanceStatusType> byId = new HashMap<String, AttendanceStatusType>();
        for (AttendanceStatusType status : statuses.active(companyId)) {
            byId.put(status.id(), status);
        }
        List<AttendanceRecord> open = new ArrayList<AttendanceRecord>();
        open.addAll(records.findByBusinessDateAndEndedOffsetSecondsIsNull(now.businessDate()));
        open.addAll(records.findByBusinessDateAndEndedOffsetSecondsIsNull(
                now.businessDate().minusDays(1)));

        List<AttendanceRecord> closed = new ArrayList<AttendanceRecord>();
        for (AttendanceRecord record : open) {
            AttendanceStatusType status = byId.get(record.statusTypeId());
            if (status == null) {
                // Another company's record, or a retired status. Either way it
                // is not this sweep's to close.
                continue;
            }
            if (!status.behaviour().expiresAutomatically()) {
                continue;
            }
            // Elapsed across the business-day boundary: yesterday's 23:00 record
            // is 25 hours old at 00:00 today, and comparing raw offsets would
            // make it look one hour young.
            long startedAbsolute = (long) record.businessDate().toEpochDay() * 86_400L
                    + record.interval().startedAt().offsetSeconds();
            long nowAbsolute = (long) now.businessDate().toEpochDay() * 86_400L
                    + now.offsetSeconds();
            long expiresAfter = status.behaviour().autoExpiresAfter().getSeconds();
            if (nowAbsolute - startedAbsolute < expiresAfter) {
                continue;
            }
            // Closed at the moment it expired, not at the moment the sweep ran:
            // a sweep that runs hourly must not make every 자리비움 look like it
            // lasted until the top of the hour.
            long expiresAt = record.interval().startedAt().offsetSeconds() + expiresAfter;
            if (expiresAt > BusinessInstant.MAX_OFFSET_SECONDS) {
                // Its expiry falls outside its own business day's window. Left
                // open and visible rather than closed at a fabricated time; a
                // record this old is a correction, not a sweep.
                continue;
            }
            record.endAt(BusinessInstant.of(record.businessDate(), (int) expiresAt));
            closed.add(records.save(record));
        }
        return Immutables.copyOf(closed);
    }

    // ------------------------------------------------------------------

    /**
     * The open record, looked for on today's business date and yesterday's.
     *
     * <p>Yesterday's because that is where a night shift lives: at 03:00 on the
     * 31st the record that needs closing is the one opened on the 30th, and
     * looking only at the 31st would find nothing and invite the caller to open
     * a second one.
     */
    private AttendanceRecord requireOpenRecord(String employeeId, LocalDateTime wallClock) {
        LocalDate[] candidates = new LocalDate[] {
            wallClock.toLocalDate(), wallClock.toLocalDate().minusDays(1)
        };
        for (int i = 0; i < candidates.length; i++) {
            Optional<AttendanceRecord> open = openIn(records
                    .findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
                            employeeId, candidates[i]));
            if (open.isPresent()) {
                return open.get();
            }
        }
        throw new AttendanceRefusedException(
                "진행 중인 근태 기록이 없습니다. (Employee " + employeeId + " has no open attendance "
                        + "record on " + candidates[0] + " or " + candidates[1] + ".)");
    }

    private void applyOverlapPolicy(String companyId, List<AttendanceRecord> day,
            BusinessInstant at) {

        OverlapPolicy policy = settings.overlapPolicy(companyId);
        if (policy == OverlapPolicy.ALLOW) {
            return;
        }
        AttendanceInterval starting = AttendanceInterval.open(at);
        for (AttendanceRecord existing : day) {
            if (!existing.interval().overlaps(starting)) {
                continue;
            }
            if (policy == OverlapPolicy.REFUSE) {
                throw new AttendanceRefusedException(
                        "이미 등록된 근태와 시간이 겹칩니다: " + existing.interval() + " (The new status at "
                                + at + " overlaps an existing record, " + existing.interval()
                                + ". This installation is configured to refuse overlaps, so one of "
                                + "the two is wrong and it is not for the system to guess which.)");
            }
            if (existing.isOpen()) {
                existing.endAt(at);
                records.save(existing);
            }
        }
    }

    /**
     * The target names the employee who owns the record, their unit, and the
     * business date it belongs to — never today's, so correcting last week's
     * night shift is authorised against the org as it stood last week.
     */
    private void authoriseWrite(PermissionPrincipal actor, String companyId, String employeeId,
            LocalDate businessDate, String description) {
        permissions.check(actor, AttendancePermissions.ATTENDANCE_WRITE,
                targets.of(companyId, employeeId, businessDate, description)).orThrow();
    }

    /** The last open record of a day, if any. Last, because the newest is the live one. */
    private static Optional<AttendanceRecord> openIn(List<AttendanceRecord> day) {
        for (int i = day.size() - 1; i >= 0; i--) {
            if (day.get(i).isOpen()) {
                return Optional.of(day.get(i));
            }
        }
        return Optional.empty();
    }
}
