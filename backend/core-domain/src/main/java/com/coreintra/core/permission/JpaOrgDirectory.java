package com.coreintra.core.permission;

import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org chart, read from the database as of a business date.
 *
 * <p>Subtree membership is answered from the materialised path — a string
 * prefix test, no recursion, no per-level query. Walking parent pointers here
 * would put an N+1 on the hottest path in the system.
 */
@Component
public class JpaOrgDirectory implements OrgDirectory {

    private final PositionRepository positions;
    private final OrgUnitRepository orgUnits;

    public JpaOrgDirectory(PositionRepository positions, OrgUnitRepository orgUnits) {
        this.positions = positions;
        this.orgUnits = orgUnits;
    }

    @Override
    @Transactional(readOnly = true)
    public PrincipalOrgState resolve(PermissionPrincipal principal, LocalDate asOf) {
        String employeeId = principal.employeeId();
        if (employeeId == null) {
            // Service accounts and support sessions hold no position. Their
            // authority comes from account-attached grants only, which is what
            // makes it enumerable.
            return PrincipalOrgState.none(null);
        }

        List<Position> held = positions.findActiveOn(employeeId, asOf);
        Set<String> unitIds = new HashSet<String>();
        Set<String> companyIds = new HashSet<String>();
        Set<String> rankIds = new HashSet<String>();
        Set<String> functionIds = new HashSet<String>();

        for (Position position : held) {
            unitIds.add(position.orgUnitId());
            rankIds.add(position.rankId());
            Optional<OrgUnit> unit = orgUnits.findById(position.orgUnitId());
            if (unit.isPresent()) {
                companyIds.add(unit.get().companyId());
            }
        }
        functionIds.addAll(jobFunctionIdsFor(held));
        return new PrincipalOrgState(employeeId, unitIds, companyIds, rankIds, functionIds);
    }

    /**
     * Job functions attached to the given positions.
     *
     * <p>Read through the join table by native query rather than an entity
     * association: an association would be a lazy collection on {@link Position},
     * and initialising it during a permission check is exactly the N+1 this
     * class is shaped to avoid.
     */
    private Set<String> jobFunctionIdsFor(List<Position> held) {
        if (held.isEmpty()) {
            return new HashSet<String>();
        }
        Set<String> positionIds = new HashSet<String>();
        for (Position position : held) {
            positionIds.add(position.id());
        }
        return new HashSet<String>(positions.findJobFunctionIds(positionIds));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInSubtree(String ancestorId, String candidateId, LocalDate asOf) {
        if (ancestorId == null || candidateId == null) {
            return false;
        }
        if (ancestorId.equals(candidateId)) {
            // Reflexive: "subtree" includes the unit itself, which is what the
            // person granting ORG_UNIT_SUBTREE means by it.
            return true;
        }
        Optional<OrgUnit> ancestor = orgUnits.findById(ancestorId);
        Optional<OrgUnit> candidate = orgUnits.findById(candidateId);
        if (!ancestor.isPresent() || !candidate.isPresent()) {
            return false;
        }
        return candidate.get().path().startsWith(ancestor.get().path());
    }
}
