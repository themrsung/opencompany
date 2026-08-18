package com.coreintra.attendance.service;

import com.coreintra.core.org.Position;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the permission target for something that belongs to one employee.
 *
 * <h2>Why the org unit has to be on it</h2>
 *
 * <p>A target carrying only the company and the owning employee can be reached
 * by a {@code SELF} grant and by a {@code COMPANY} one, and by nothing in
 * between. That leaves out the case the feature is mostly for: a 팀장 who may
 * correct their own team's attendance and nobody else's holds an
 * {@code ORG_UNIT} grant, and the evaluator cannot apply it to a target that
 * names no unit.
 *
 * <p>So the owner's unit is resolved here, <em>as of the business date the
 * record belongs to</em>. Correcting last quarter's timesheet is authorised
 * against the team as it was last quarter, which is the team that lived through
 * it.
 *
 * <p>Costs one query per authorised operation. That is the price of row-level
 * authorisation on the domain object rather than on the URL, and it is paid on
 * writes and on single-employee reads — never inside the who's-in loop, which
 * authorises the board once.
 */
@Component
public class EmployeeTargets {

    private final PositionRepository positions;

    public EmployeeTargets(PositionRepository positions) {
        this.positions = positions;
    }

    PermissionTarget of(String companyId, String employeeId, LocalDate businessDate,
            String description) {
        return PermissionTarget.builder()
                .companyId(companyId)
                .orgUnitId(unitOf(employeeId, businessDate))
                .ownerEmployeeId(employeeId)
                .asOfBusinessDate(businessDate)
                .description(description)
                .build();
    }

    /** The primary unit if there is one, otherwise the first held. Null for a leaver. */
    private String unitOf(String employeeId, LocalDate businessDate) {
        if (employeeId == null) {
            return null;
        }
        List<Position> held = positions.findActiveOn(employeeId, businessDate);
        String unitId = null;
        for (Position position : held) {
            if (position.isPrimary()) {
                return position.orgUnitId();
            }
            if (unitId == null) {
                unitId = position.orgUnitId();
            }
        }
        return unitId;
    }
}
