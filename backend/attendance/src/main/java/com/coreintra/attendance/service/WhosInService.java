package com.coreintra.attendance.service;

import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.attendance.repository.AttendanceRecordRepository;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근무 현황 — the who's-in board and the team calendar.
 *
 * <h2>What it costs</h2>
 *
 * <p><b>Three queries for a team</b>, regardless of how many people are on the
 * board: one for the unit's positions on that date, one for the day's attendance
 * records across the whole set of employees ({@code attendance_record} is
 * indexed on {@code (employee_id, business_date, started_offset_seconds)}), and
 * one for the company's status types. Everything else is a map lookup. A
 * company-wide board pays one position query per org unit instead of one, which
 * is tens rather than thousands.
 *
 * <p>The version to avoid is the obvious one: fetch the team, then ask for each
 * person's current status. That is 1 + N and it is slowest at 09:05 when the
 * whole company is looking at it.
 *
 * <h2>visibleToPeers is applied here, not in the query</h2>
 *
 * <p>Deliberately: a private status must still be visible <em>to the person in
 * it</em>, and filtering in SQL would need the viewer's identity woven into the
 * predicate to get that right. It is applied per row against the viewer's own
 * employee id, and a hidden status yields a masked entry rather than a missing
 * one — see {@link WhoIsInEntry}.
 *
 * <h2>One permission check, not one per person</h2>
 *
 * <p>The board is a single view of a team, authorised once against the company
 * and unit. Checking per employee would multiply the evaluator by the headcount
 * to answer a question that is the same every time.
 */
@Service
public class WhosInService {

    private final AttendanceRecordRepository records;
    private final AttendanceStatusService statuses;
    private final PositionRepository positions;
    private final OrgUnitRepository orgUnits;
    private final PermissionEvaluator permissions;

    public WhosInService(AttendanceRecordRepository records, AttendanceStatusService statuses,
            PositionRepository positions, OrgUnitRepository orgUnits,
            PermissionEvaluator permissions) {
        this.records = records;
        this.statuses = statuses;
        this.positions = positions;
        this.orgUnits = orgUnits;
        this.permissions = permissions;
    }

    /**
     * The board for an org unit on a business date.
     *
     * <p>Membership is resolved as of that date, so last month's board shows
     * last month's team rather than today's.
     */
    @Transactional(readOnly = true)
    public List<WhoIsInEntry> forUnit(PermissionPrincipal actor, String companyId,
            String orgUnitId, LocalDate businessDate) {

        List<String> employeeIds = new ArrayList<String>();
        for (Position position : positions.findInUnitOn(orgUnitId, businessDate)) {
            if (!employeeIds.contains(position.employeeId())) {
                employeeIds.add(position.employeeId());
            }
        }
        return forEmployees(actor, companyId, orgUnitId, employeeIds, businessDate);
    }

    /**
     * The board for a whole company.
     *
     * <p>Costs one query per org unit to resolve membership, plus the two the
     * board itself needs. Units are tens, not thousands, so this is a small
     * fixed cost rather than a scan — and it is the honest one: there is no
     * "employees of a company as of a date" query to use instead.
     */
    @Transactional(readOnly = true)
    public List<WhoIsInEntry> forCompany(PermissionPrincipal actor, String companyId,
            LocalDate businessDate) {

        List<String> employeeIds = new ArrayList<String>();
        for (OrgUnit unit : orgUnits.findByCompanyIdAndActiveTrue(companyId)) {
            for (Position position : positions.findInUnitOn(unit.id(), businessDate)) {
                if (!employeeIds.contains(position.employeeId())) {
                    employeeIds.add(position.employeeId());
                }
            }
        }
        return forEmployees(actor, companyId, null, employeeIds, businessDate);
    }

    /**
     * The board for a named set of people.
     *
     * <p><b>Not public, deliberately.</b> The permission target here is built
     * from an org unit the <em>caller</em> names, and the people are a list the
     * caller supplies; nothing makes the two agree. A public caller could
     * therefore ask about a unit they can see while naming employees from one
     * they cannot. {@link #forUnit} and {@link #forCompany} both derive the
     * people from the scope they authorise, which is what closes that gap, and
     * they are the entry points a controller should have.
     *
     * @param orgUnitId the unit the view is scoped to; null for a company-wide
     *        board, which then needs a company-scoped grant
     */
    @Transactional(readOnly = true)
    List<WhoIsInEntry> forEmployees(PermissionPrincipal actor, String companyId,
            String orgUnitId, List<String> employeeIds, LocalDate businessDate) {

        permissions.check(actor, AttendancePermissions.ATTENDANCE_READ,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .orgUnitId(orgUnitId)
                        .asOfBusinessDate(businessDate)
                        .description("who's in on " + businessDate)
                        .build()).orThrow();

        if (employeeIds == null || employeeIds.isEmpty()) {
            return Immutables.<WhoIsInEntry>listOf();
        }

        Map<String, AttendanceStatusType> statusById =
                new HashMap<String, AttendanceStatusType>();
        for (AttendanceStatusType status : statuses.active(companyId)) {
            statusById.put(status.id(), status);
        }

        // Newest record per employee wins: the query returns the day in start
        // order, so the last one seen for a person is their current status.
        Map<String, AttendanceRecord> latest = new LinkedHashMap<String, AttendanceRecord>();
        for (AttendanceRecord record : records.findDayForEmployees(businessDate, employeeIds)) {
            latest.put(record.employeeId(), record);
        }

        String viewerEmployeeId = actor.employeeId();
        List<WhoIsInEntry> board = new ArrayList<WhoIsInEntry>(employeeIds.size());
        for (String employeeId : employeeIds) {
            AttendanceRecord record = latest.get(employeeId);
            if (record == null) {
                board.add(WhoIsInEntry.unrecorded(employeeId));
                continue;
            }
            AttendanceStatusType status = statusById.get(record.statusTypeId());
            if (status == null) {
                // A retired status still names itself on old records; it is
                // simply no longer in the active list.
                board.add(WhoIsInEntry.unrecorded(employeeId));
                continue;
            }
            boolean own = employeeId.equals(viewerEmployeeId);
            if (!status.behaviour().visibleToPeers() && !own) {
                board.add(WhoIsInEntry.hidden(employeeId));
                continue;
            }
            board.add(new WhoIsInEntry(employeeId, status.code(), status.labelKo(),
                    status.labelEn(), status.colour(), status.icon(),
                    status.behaviour().countsAsWorking(), true, record.interval()));
        }
        return Immutables.copyOf(board);
    }
}
