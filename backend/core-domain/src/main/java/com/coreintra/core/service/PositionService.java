package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a person stands: the tuple (employee, unit, rank, 직무[]) between two
 * dates.
 *
 * <h2>Nothing is edited in place</h2>
 *
 * <p>A promotion closes the old row and opens a new one. This is the whole
 * reason a permission check on a past-dated document resolves against the org as
 * it was: rewriting the rank on the existing row would silently re-answer every
 * approval that was signed under the old one, and nobody would be able to see
 * that it happened. {@link #reassign} is the only supported way to change a
 * rank, a unit or a 직무 set, and it is a close plus an open in one transaction.
 *
 * <h2>What "overlapping" means here</h2>
 *
 * <p>겸직 is real: {@link Position#isPrimary()} exists precisely because a person
 * can hold several posts at once, and the permission scope walks all of them. So
 * concurrent positions in <em>different</em> units are allowed and are not an
 * error.
 *
 * <p>What is refused is two live rows for the same person in the <em>same</em>
 * unit. "Which rank does this person hold in this unit on date D" has to have
 * exactly one answer, or the approval rule "the 팀장 of the drafter's unit"
 * resolves to two people and a reorder or a retirement cannot say whose seat it
 * moved.
 */
@Service
public class PositionService {

    private final PositionAssignmentRepository positions;
    private final OrgUnitCatalogRepository units;
    private final RankCatalogRepository ranks;
    private final JobFunctionCatalogRepository jobFunctions;
    private final EmployeeService employeeService;
    private final PermissionEvaluator evaluator;

    public PositionService(PositionAssignmentRepository positions, OrgUnitCatalogRepository units,
            RankCatalogRepository ranks, JobFunctionCatalogRepository jobFunctions, EmployeeService employeeService,
            PermissionEvaluator evaluator) {
        if (positions == null) {
            throw new NullPointerException("positions");
        }
        if (units == null) {
            throw new NullPointerException("units");
        }
        if (ranks == null) {
            throw new NullPointerException("ranks");
        }
        if (jobFunctions == null) {
            throw new NullPointerException("jobFunctions");
        }
        if (employeeService == null) {
            throw new NullPointerException("employeeService");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.positions = positions;
        this.units = units;
        this.ranks = ranks;
        this.jobFunctions = jobFunctions;
        this.employeeService = employeeService;
        this.evaluator = evaluator;
    }

    /**
     * One person's whole history, oldest first, closed rows included.
     *
     * <p>The closed rows are the point: they are the record of who could approve
     * what, and when.
     */
    @Transactional(readOnly = true)
    public List<Position> history(PermissionPrincipal caller, String employeeId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee employee = employeeService.require(employeeId);
        evaluator.check(caller, OrgPermissions.POSITION_READ, employeeService.target(employee, businessDate))
                .orThrow();
        return Immutables.copyOf(positions.findByEmployeeIdOrderByEffectiveFromAsc(employee.id()));
    }

    /**
     * Puts a person in a seat from a date.
     *
     * <p>Both ends are checked: taking authority over the person, and adding to the
     * destination unit. A manager who may staff their own team cannot pull somebody
     * out of a team they have no authority over by naming them here.
     */
    @Transactional
    public Position assign(PermissionPrincipal caller, String employeeId, String orgUnitId, String rankId,
            List<String> jobFunctionIds, LocalDate effectiveFrom, boolean primary, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Employee employee = employeeService.require(employeeId);
        evaluator.check(caller, OrgPermissions.POSITION_ASSIGN, employeeService.target(employee, businessDate))
                .orThrow();

        OrgUnit unit = requireUnit(employee.companyId(), orgUnitId);
        evaluator.check(caller, OrgPermissions.POSITION_ASSIGN, unitTarget(unit, businessDate)).orThrow();

        Rank rank = requireRank(employee.companyId(), rankId);
        LocalDate from = requireDate(effectiveFrom);
        Set<String> functions = distinctFunctions(employee.companyId(), jobFunctionIds);
        refuseOverlap(employee.id(), unit.id(), from, null);
        return open(employee, unit, rank, functions, from, primary);
    }

    /**
     * A promotion, a transfer, or a change of 직무: closes the old row on the day
     * the new one begins and opens the successor.
     *
     * <p>One transaction, because a half-applied reassignment is a person standing
     * in two seats or none, and both of those change who may approve what.
     */
    @Transactional
    public Position reassign(PermissionPrincipal caller, String positionId, String newOrgUnitId, String newRankId,
            List<String> jobFunctionIds, LocalDate effectiveFrom, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Position current = require(positionId);
        Employee employee = employeeService.require(current.employeeId());
        LocalDate from = requireDate(effectiveFrom);

        OrgUnit oldUnit = requireUnit(employee.companyId(), current.orgUnitId());
        OrgUnit newUnit = requireUnit(employee.companyId(), newOrgUnitId);
        Rank rank = requireRank(employee.companyId(), newRankId);

        evaluator.check(caller, OrgPermissions.POSITION_ASSIGN, employeeService.target(employee, businessDate))
                .orThrow();
        evaluator.check(caller, OrgPermissions.POSITION_CLOSE, unitTarget(oldUnit, businessDate)).orThrow();
        evaluator.check(caller, OrgPermissions.POSITION_ASSIGN, unitTarget(newUnit, businessDate)).orThrow();

        if (current.effectiveTo() != null) {
            throw new IllegalArgumentException("position ended on " + current.effectiveTo()
                    + " and cannot be reassigned; assign a new one instead");
        }
        // Everything is checked before anything is touched, including the row being
        // superseded - which is excluded from the overlap test rather than closed
        // early, so that a 직무 typo cannot leave the old seat vacated and the new
        // one unopened.
        Set<String> functions = distinctFunctions(employee.companyId(), jobFunctionIds);
        refuseOverlap(employee.id(), newUnit.id(), from, current.id());

        current.closeOn(from);
        positions.save(current);
        return open(employee, newUnit, rank, functions, from, current.isPrimary());
    }

    /**
     * Ends an assignment on the first day it is no longer held.
     *
     * <p>The row stays. A closed position is what makes last quarter's approval
     * chain re-checkable, and deleting it would leave documents signed by an
     * authority that, on the evidence, never existed.
     */
    @Transactional
    public Position close(PermissionPrincipal caller, String positionId, LocalDate endsOn, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Position position = require(positionId);
        Employee employee = employeeService.require(position.employeeId());
        OrgUnit unit = requireUnit(employee.companyId(), position.orgUnitId());

        evaluator.check(caller, OrgPermissions.POSITION_CLOSE, employeeService.target(employee, businessDate))
                .orThrow();
        evaluator.check(caller, OrgPermissions.POSITION_CLOSE, unitTarget(unit, businessDate)).orThrow();

        position.closeOn(requireDate(endsOn));
        return positions.save(position);
    }

    /** Writes the row. Everything it could refuse has been refused by now. */
    private Position open(Employee employee, OrgUnit unit, Rank rank, Set<String> functions,
            LocalDate effectiveFrom, boolean primary) {
        // Opens open-ended on purpose: a position ends when somebody records that
        // it ended, not when it was planned to.
        Position position = new Position(UUID.randomUUID().toString(), employee.id(), unit.id(), rank.id(),
                effectiveFrom);
        position.setPrimary(primary);
        Position saved = positions.save(position);
        for (String jobFunctionId : functions) {
            positions.linkJobFunction(saved.id(), jobFunctionId);
        }
        return saved;
    }

    /**
     * Refuses a second live row for the same person in the same unit.
     *
     * <p>The new row is open-ended, so it overlaps anything in that unit that has
     * not already ended by the day it starts. Concurrent posts in other units are
     * 겸직 and are left alone.
     *
     * @param supersededPositionId the row about to be closed on the same day, or
     *        null; it is not an overlap with its own successor
     */
    private void refuseOverlap(String employeeId, String orgUnitId, LocalDate effectiveFrom,
            String supersededPositionId) {
        for (Position existing : positions.findByEmployeeIdOrderByEffectiveFromAsc(employeeId)) {
            if (!existing.orgUnitId().equals(orgUnitId) || existing.id().equals(supersededPositionId)) {
                continue;
            }
            boolean endedBeforeItStarts = existing.effectiveTo() != null
                    && !existing.effectiveTo().isAfter(effectiveFrom);
            if (!endedBeforeItStarts) {
                throw new IllegalArgumentException("employee already holds a position in this unit from "
                        + existing.effectiveFrom()
                        + (existing.effectiveTo() == null ? " (open)" : " until " + existing.effectiveTo())
                        + "; close it before opening another from " + effectiveFrom);
            }
        }
    }

    /** 직무 ids, de-duplicated in the order given, each checked to be a live one of this company. */
    private Set<String> distinctFunctions(String companyId, List<String> jobFunctionIds) {
        Set<String> distinct = new LinkedHashSet<String>();
        if (jobFunctionIds == null) {
            return distinct;
        }
        for (String jobFunctionId : jobFunctionIds) {
            String id = Arguments.required(jobFunctionId, "jobFunctionId");
            Optional<JobFunction> found = jobFunctions.findById(id);
            if (!found.isPresent()) {
                throw RecordNotFoundException.of("job function", id);
            }
            JobFunction jobFunction = found.get();
            if (!jobFunction.companyId().equals(companyId)) {
                throw new IllegalArgumentException("job function " + jobFunction.code()
                        + " does not belong to company " + companyId);
            }
            if (!jobFunction.isActive()) {
                throw new IllegalArgumentException("job function " + jobFunction.labelKo() + " is retired");
            }
            distinct.add(id);
        }
        return distinct;
    }

    private Position require(String positionId) {
        Optional<Position> found = positions.findById(Arguments.required(positionId, "positionId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("position", positionId);
        }
        return found.get();
    }

    private OrgUnit requireUnit(String companyId, String orgUnitId) {
        Optional<OrgUnit> found = units.findById(Arguments.required(orgUnitId, "orgUnitId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("org unit", orgUnitId);
        }
        OrgUnit unit = found.get();
        if (!unit.companyId().equals(companyId)) {
            throw new IllegalArgumentException("unit " + unit.code() + " does not belong to company " + companyId);
        }
        if (!unit.isActive()) {
            throw new IllegalArgumentException("unit " + unit.code() + " is retired");
        }
        return unit;
    }

    private Rank requireRank(String companyId, String rankId) {
        Optional<Rank> found = ranks.findById(Arguments.required(rankId, "rankId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("rank", rankId);
        }
        Rank rank = found.get();
        if (!rank.companyId().equals(companyId)) {
            throw new IllegalArgumentException("rank " + rank.code() + " does not belong to company " + companyId);
        }
        if (!rank.isActive()) {
            throw new IllegalArgumentException("rank " + rank.labelKo() + " is retired");
        }
        return rank;
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            // The business date of the request is not the business date of the
            // assignment: a transfer is usually recorded before or after it happens.
            throw new IllegalArgumentException("effective date is required");
        }
        return date;
    }

    private static PermissionTarget unitTarget(OrgUnit unit, LocalDate businessDate) {
        return OrgTargets.unit(unit.companyId(), unit.id(), businessDate, "unit " + unit.code());
    }

    /**
     * Who is standing in a unit on a date.
     *
     * <p>Checked against the unit rather than row by row: the caller is asking
     * about the unit, and an answer with some of its people missing would read as
     * an empty team rather than a partial view.
     */
    @Transactional(readOnly = true)
    public List<Position> liveInUnit(PermissionPrincipal caller, String orgUnitId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Optional<OrgUnit> found = units.findById(Arguments.required(orgUnitId, "orgUnitId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("org unit", orgUnitId);
        }
        OrgUnit unit = found.get();
        evaluator.check(caller, OrgPermissions.POSITION_READ, unitTarget(unit, businessDate)).orThrow();
        List<Position> live = new ArrayList<Position>(positions.findLiveInUnit(unit.id(), businessDate));
        return Immutables.copyOf(live);
    }
}
