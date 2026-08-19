package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org tree: 본부, 팀, whatever the client calls them.
 *
 * <h2>Why a move is written the way it is</h2>
 *
 * <p>{@link OrgUnit} keeps a materialised path so that an ORG_UNIT_SUBTREE check
 * is a prefix match on an indexed column instead of a recursive walk. The price
 * is that the path is derived state: moving a unit invalidates the path of
 * everything beneath it, and {@link OrgUnit#attachTo} says as much - callers must
 * fix the descendants. A half-done move does not throw; it produces a subtree
 * whose paths point at where it used to be, so every subtree-scoped grant above
 * it silently stops reaching, and nothing in the request that caused it mentioned
 * permissions.
 *
 * <p>So the move here is not a loop over pointers. It reads the whole company
 * ordered by path, which puts every parent in front of its children, re-attaches
 * the moved unit, then re-attaches each descendant to its own parent in that
 * order - by which time the parent's path is already correct. Cycles are refused
 * before anything is touched, using {@link OrgUnit#contains}, which is the same
 * prefix test the permission scope uses: if the destination is inside the subtree
 * being moved, it is inside it by exactly the definition the evaluator will apply
 * afterwards. The whole rewrite is one transaction and one {@code saveAll}, so a
 * failure leaves the old tree rather than half of a new one.
 */
@Service
public class OrgUnitService {

    private final OrgUnitCatalogRepository units;
    private final PositionAssignmentRepository positions;
    private final PermissionEvaluator evaluator;

    public OrgUnitService(OrgUnitCatalogRepository units, PositionAssignmentRepository positions,
            PermissionEvaluator evaluator) {
        if (units == null) {
            throw new NullPointerException("units");
        }
        if (positions == null) {
            throw new NullPointerException("positions");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.units = units;
        this.positions = positions;
        this.evaluator = evaluator;
    }

    /**
     * The active tree of one company, parents before children.
     *
     * <p>Filtered by what the caller may read, so a 본부장 with an ORG_UNIT_SUBTREE
     * grant sees their own branch and not the rest of the company. The ordering
     * survives the filter, which is what lets the UI render an indented tree from
     * the list without sorting it again.
     */
    @Transactional(readOnly = true)
    public List<OrgUnit> tree(PermissionPrincipal caller, String companyId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        List<OrgUnit> visible = new ArrayList<OrgUnit>();
        for (OrgUnit unit : units.findByCompanyIdOrderByPathAsc(Arguments.required(companyId, "companyId"))) {
            if (!unit.isActive()) {
                continue;
            }
            if (evaluator.check(caller, OrgPermissions.UNIT_READ, target(unit, businessDate)).isAllowed()) {
                visible.add(unit);
            }
        }
        return Immutables.copyOf(visible);
    }

    @Transactional(readOnly = true)
    public OrgUnit read(PermissionPrincipal caller, String unitId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        OrgUnit unit = require(unitId);
        evaluator.check(caller, OrgPermissions.UNIT_READ, target(unit, businessDate)).orThrow();
        return unit;
    }

    /**
     * Adds a unit, optionally under a parent.
     *
     * <p>Checked against the parent rather than the new unit, which has no id yet
     * and no place in anyone's grants: adding a 팀 under a 본부 is authority over
     * that 본부. A root unit is checked against the company.
     */
    @Transactional
    public OrgUnit create(PermissionPrincipal caller, String companyId, String parentUnitId, String code,
            String nameKo, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        String cleanCode = code(code);
        String cleanName = Arguments.required(nameKo, "nameKo");

        List<OrgUnit> catalog = units.findByCompanyIdOrderByPathAsc(company);

        // Authorise before validating. A caller who may not create units here
        // should not be able to map the company's codes by reading which ones
        // come back "already in use".
        OrgUnit parent = null;
        if (!Texts.isBlank(parentUnitId)) {
            parent = requireIn(catalog, company, Texts.strip(parentUnitId));
            evaluator.check(caller, OrgPermissions.UNIT_CREATE, target(parent, businessDate)).orThrow();
            if (!parent.isActive()) {
                throw new IllegalArgumentException("unit " + parent.code() + " is retired; it cannot take children");
            }
        } else {
            evaluator.check(caller, OrgPermissions.UNIT_CREATE,
                    OrgTargets.company(company, businessDate, "root of company " + company)).orThrow();
        }
        refuseDuplicateCode(catalog, cleanCode, null);

        OrgUnit unit = new OrgUnit(UUID.randomUUID().toString(), company, cleanCode, cleanName);
        if (parent != null) {
            unit.attachTo(parent);
        }
        return units.save(unit);
    }

    @Transactional
    public OrgUnit rename(PermissionPrincipal caller, String unitId, String nameKo, String nameEn,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        OrgUnit unit = require(unitId);
        evaluator.check(caller, OrgPermissions.UNIT_UPDATE, target(unit, businessDate)).orThrow();
        unit.rename(Arguments.required(nameKo, "nameKo"), Arguments.optional(nameEn));
        return units.save(unit);
    }

    /** Display order among siblings. Client-defined; never inferred from the name. */
    @Transactional
    public OrgUnit setSortOrder(PermissionPrincipal caller, String unitId, int sortOrder, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        OrgUnit unit = require(unitId);
        evaluator.check(caller, OrgPermissions.UNIT_UPDATE, target(unit, businessDate)).orThrow();
        unit.setSortOrder(sortOrder);
        return units.save(unit);
    }

    /**
     * Re-parents a unit and rewrites the path of everything beneath it.
     *
     * <p>Both ends are checked: a move takes a subtree out of one manager's reach
     * and puts it into another's, and authority over the unit being moved is only
     * half of that. Passing a null parent makes the unit a root.
     */
    @Transactional
    public OrgUnit move(PermissionPrincipal caller, String unitId, String newParentUnitId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        OrgUnit loaded = require(unitId);
        List<OrgUnit> catalog = units.findByCompanyIdOrderByPathAsc(loaded.companyId());
        // Work on the catalog's own instance throughout, so the unit being moved
        // and its descendants are the same objects the rewrite below walks.
        OrgUnit unit = requireIn(catalog, loaded.companyId(), loaded.id());

        OrgUnit newParent = Texts.isBlank(newParentUnitId)
                ? null
                : requireIn(catalog, unit.companyId(), Texts.strip(newParentUnitId));

        // Authorised first, then validated: whether a move would make a cycle is a
        // fact about the tree, and a caller who may not move this unit has no
        // business learning it.
        evaluator.check(caller, OrgPermissions.UNIT_MOVE, target(unit, businessDate)).orThrow();
        evaluator.check(caller, OrgPermissions.UNIT_MOVE, newParent == null
                ? OrgTargets.company(unit.companyId(), businessDate, "root of company " + unit.companyId())
                : target(newParent, businessDate)).orThrow();

        if (newParent != null) {
            if (!newParent.isActive()) {
                throw new IllegalArgumentException(
                        "unit " + newParent.code() + " is retired; it cannot take children");
            }
            // The prefix test, not a pointer walk: "inside the subtree" here means
            // exactly what ORG_UNIT_SUBTREE will mean afterwards. Reflexive, so a
            // unit cannot be made its own parent either.
            if (unit.contains(newParent)) {
                throw new IllegalArgumentException("unit " + unit.code() + " cannot be moved under "
                        + newParent.code() + ": that unit is inside it, and the move would create a cycle");
            }
        }

        List<OrgUnit> subtree = descendantsOf(catalog, unit);
        Map<String, OrgUnit> byId = new HashMap<String, OrgUnit>();
        byId.put(unit.id(), unit);
        for (OrgUnit descendant : subtree) {
            byId.put(descendant.id(), descendant);
        }


        unit.attachTo(newParent);
        for (OrgUnit descendant : subtree) {
            // Path order guarantees this parent has already been rewritten.
            OrgUnit parent = byId.get(descendant.parentId());
            if (parent == null) {
                throw new IllegalStateException("unit " + descendant.code() + " lost its parent during a move");
            }
            descendant.attachTo(parent);
        }

        List<OrgUnit> rewritten = new ArrayList<OrgUnit>();
        rewritten.add(unit);
        rewritten.addAll(subtree);
        units.saveAll(rewritten);
        return unit;
    }

    /**
     * Takes a unit out of use.
     *
     * <p>Refused while anything still hangs off it. A retired unit with live
     * children is the orphaned subtree in another disguise - the rows are intact,
     * but they sit under something nobody can see or grant against - and a retired
     * unit with live positions is a set of people whose place in the chart has
     * quietly stopped existing, along with every grant that reached them through
     * it.
     */
    @Transactional
    public OrgUnit retire(PermissionPrincipal caller, String unitId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        OrgUnit unit = require(unitId);
        evaluator.check(caller, OrgPermissions.UNIT_RETIRE, target(unit, businessDate)).orThrow();

        List<OrgUnit> catalog = units.findByCompanyIdOrderByPathAsc(unit.companyId());
        for (OrgUnit descendant : descendantsOf(catalog, unit)) {
            if (descendant.isActive()) {
                throw new IllegalArgumentException("unit " + unit.code() + " still has active units beneath it ("
                        + descendant.code() + "); retire or move them first");
            }
        }
        List<Position> live = positions.findLiveInUnit(unit.id(), businessDate);
        if (!live.isEmpty()) {
            throw new IllegalArgumentException("unit " + unit.code() + " still holds " + live.size()
                    + " live position(s) on " + businessDate + "; move or close them first");
        }
        unit.deactivate();
        return units.save(unit);
    }

    /** Everything strictly beneath a unit, parents before children. */
    private static List<OrgUnit> descendantsOf(List<OrgUnit> catalog, OrgUnit unit) {
        List<OrgUnit> descendants = new ArrayList<OrgUnit>();
        for (OrgUnit candidate : catalog) {
            if (!candidate.id().equals(unit.id()) && unit.contains(candidate)) {
                descendants.add(candidate);
            }
        }
        return descendants;
    }

    private static void refuseDuplicateCode(List<OrgUnit> catalog, String code, String exceptUnitId) {
        for (OrgUnit unit : catalog) {
            if (unit.code().equals(code) && !unit.id().equals(exceptUnitId)) {
                // Retired units count: org_unit_code_unique_per_company does not
                // exclude them, so the friendly message has to come from here.
                throw new IllegalArgumentException("unit code is already in use in this company: " + code);
            }
        }
    }

    private static OrgUnit requireIn(List<OrgUnit> catalog, String companyId, String unitId) {
        for (OrgUnit unit : catalog) {
            if (unit.id().equals(unitId)) {
                return unit;
            }
        }
        throw new IllegalArgumentException("unit " + unitId + " does not belong to company " + companyId);
    }

    private OrgUnit require(String unitId) {
        Optional<OrgUnit> found = units.findById(Arguments.required(unitId, "unitId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("org unit", unitId);
        }
        return found.get();
    }

    /**
     * A unit code is part of the path, so it cannot carry the delimiter.
     *
     * <p>{@code /hq/sales/} split by a code containing a slash stops being a
     * prefix test and starts being a guess.
     */
    private static String code(String value) {
        String cleanCode = Arguments.required(value, "code");
        if (cleanCode.indexOf('/') >= 0) {
            throw new IllegalArgumentException("unit code must not contain '/': " + cleanCode);
        }
        return cleanCode;
    }

    private static PermissionTarget target(OrgUnit unit, LocalDate businessDate) {
        return OrgTargets.unit(unit.companyId(), unit.id(), businessDate, "unit " + unit.code());
    }
}
