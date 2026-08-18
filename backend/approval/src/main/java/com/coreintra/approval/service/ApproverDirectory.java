package com.coreintra.approval.service;

import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.EmployeeRepository;
import com.coreintra.core.org.repository.JobFunctionRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.RankRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a {@link RoleExpression} into the people it names, as of a business date.
 *
 * <h2>Why this is a separate object</h2>
 *
 * <p>Resolution runs exactly once per document, at submission, and its answer is
 * then frozen onto the document. Keeping it in one place makes that single
 * moment easy to find and easy to test: everything else in the module consumes a
 * snapshot and never asks the org chart anything.
 *
 * <h2>Cost</h2>
 *
 * <p>One query per org unit in the expression's domain, plus one scan of
 * {@code user_account} to map employees to accounts. That scan is the honest
 * weak point: {@code UserAccountRepository} has no {@code findByEmployeeIdIn},
 * and adding one belongs to the module that owns it. It is paid once per
 * submission — never on the inbox path — so it is a startup-shaped cost, not a
 * per-request one.
 */
@Service
public class ApproverDirectory {

    /** A role expression that named nobody. Refused loudly, never silently dropped. */
    public static class UnresolvableRoleException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public UnresolvableRoleException(String message) {
            super(message);
        }
    }

    private final OrgUnitRepository orgUnits;
    private final PositionRepository positions;
    private final RankRepository ranks;
    private final JobFunctionRepository jobFunctions;
    private final EmployeeRepository employees;
    private final UserAccountRepository accounts;
    private final PermissionEvaluator permissions;

    public ApproverDirectory(OrgUnitRepository orgUnits, PositionRepository positions,
            RankRepository ranks, JobFunctionRepository jobFunctions, EmployeeRepository employees,
            UserAccountRepository accounts, PermissionEvaluator permissions) {
        this.orgUnits = orgUnits;
        this.positions = positions;
        this.ranks = ranks;
        this.jobFunctions = jobFunctions;
        this.employees = employees;
        this.accounts = accounts;
        this.permissions = permissions;
    }

    /**
     * The people this expression names on {@code context}'s business date.
     *
     * <p>Ordered most senior first, then by name, so two resolutions of the same
     * expression on the same date produce the same snapshot in the same order —
     * a 결재란 that reshuffles between renders looks tampered with.
     *
     * @return never null; may be empty, which the caller must treat as a refusal
     *         for a required step rather than as an approval that can be skipped
     */
    @Transactional(readOnly = true)
    public List<ResolvedApprover> resolve(RoleExpression role, ApprovalContext context) {
        if (role == null) {
            throw new NullPointerException("role");
        }
        List<ResolvedApprover> resolved;
        switch (role.selector()) {
            case ACCOUNT:
                resolved = resolveAccount(role.value());
                break;
            case RANK:
                resolved = resolveByRank(role, context);
                break;
            case JOB_FUNCTION:
                resolved = resolveByJobFunction(role, context);
                break;
            case REPRESENTATIVE:
                resolved = resolveRepresentatives(context);
                break;
            case PERMISSION:
                resolved = resolveByPermission(role, context);
                break;
            default:
                throw new UnresolvableRoleException("unhandled selector " + role.selector());
        }
        Collections.sort(resolved, SENIOR_FIRST);
        return Immutables.copyOf(resolved);
    }

    /**
     * The company's 대표이사s on a date.
     *
     * <p>Read from the {@code representative} flag on 직급 rather than by matching
     * the label 대표, so a client who calls the post something else still gets a
     * working joint-representation quorum.
     */
    @Transactional(readOnly = true)
    public List<ResolvedApprover> representatives(String companyId, LocalDate asOf) {
        return resolve(RoleExpression.representative(),
                new ApprovalContext(companyId, null, null, null, asOf));
    }

    private List<ResolvedApprover> resolveAccount(String accountId) {
        Optional<UserAccount> account = accounts.findById(accountId);
        List<ResolvedApprover> resolved = new ArrayList<ResolvedApprover>();
        if (!account.isPresent() || !account.get().isActive()) {
            return resolved;
        }
        resolved.add(new ResolvedApprover(account.get().id(), account.get().displayName(), null, 0));
        return resolved;
    }

    private List<ResolvedApprover> resolveByRank(RoleExpression role, ApprovalContext context) {
        Rank rank = rankByCode(context.companyId(), role.value());
        if (rank == null) {
            throw new UnresolvableRoleException(
                    "no 직급 with code \"" + role.value() + "\" exists in company "
                            + context.companyId() + ". A template naming a rank that was renamed "
                            + "or retired would route nowhere, so this is refused rather than "
                            + "producing an empty step.");
        }
        List<Position> held = positionsInDomain(role, context);
        List<ResolvedApprover> resolved = new ArrayList<ResolvedApprover>();
        Map<String, UserAccount> byEmployee = accountsByEmployeeId();
        Set<String> seen = new LinkedHashSet<String>();
        for (Position position : held) {
            if (!rank.id().equals(position.rankId())) {
                continue;
            }
            addIfEmployed(resolved, seen, byEmployee, position, rank, context.businessDate());
        }
        return resolved;
    }

    private List<ResolvedApprover> resolveByJobFunction(RoleExpression role,
            ApprovalContext context) {
        JobFunction function = jobFunctionByCode(context.companyId(), role.value());
        if (function == null) {
            throw new UnresolvableRoleException(
                    "no 직무 with code \"" + role.value() + "\" exists in company "
                            + context.companyId());
        }
        List<Position> held = positionsInDomain(role, context);
        if (held.isEmpty()) {
            return new ArrayList<ResolvedApprover>();
        }
        // One query per position, because the only join-table query available
        // returns a DISTINCT set of function ids and so cannot say which
        // position held which. A {@code findPositionIdsByJobFunction} on
        // PositionRepository would make this one query; that repository belongs
        // to core-domain, so this is the honest cost until it exists.
        Set<String> functionPositionIds = new LinkedHashSet<String>();
        for (Position position : held) {
            List<String> functionIds = positions.findJobFunctionIds(
                    Immutables.listOf(position.id()));
            if (functionIds.contains(function.id())) {
                functionPositionIds.add(position.id());
            }
        }
        List<ResolvedApprover> resolved = new ArrayList<ResolvedApprover>();
        Map<String, UserAccount> byEmployee = accountsByEmployeeId();
        Map<String, Rank> ranksById = ranksById(context.companyId());
        Set<String> seen = new LinkedHashSet<String>();
        for (Position position : held) {
            if (!functionPositionIds.contains(position.id())) {
                continue;
            }
            addIfEmployed(resolved, seen, byEmployee, position, ranksById.get(position.rankId()),
                    context.businessDate());
        }
        return resolved;
    }

    private List<ResolvedApprover> resolveRepresentatives(ApprovalContext context) {
        List<Rank> representativeRanks =
                ranks.findByCompanyIdAndRepresentativeTrueAndActiveTrue(context.companyId());
        if (representativeRanks.isEmpty()) {
            throw new UnresolvableRoleException(
                    "company " + context.companyId() + " has no 직급 marked as representative, so "
                            + "no 대표자 결재 can be routed. Mark the 대표 rank as representative "
                            + "before submitting anything that needs one.");
        }
        Map<String, Rank> wanted = new LinkedHashMap<String, Rank>();
        for (Rank rank : representativeRanks) {
            wanted.put(rank.id(), rank);
        }
        List<ResolvedApprover> resolved = new ArrayList<ResolvedApprover>();
        Map<String, UserAccount> byEmployee = accountsByEmployeeId();
        Set<String> seen = new LinkedHashSet<String>();
        for (Position position : positionsInCompany(context.companyId(), context.businessDate())) {
            Rank rank = wanted.get(position.rankId());
            if (rank == null) {
                continue;
            }
            addIfEmployed(resolved, seen, byEmployee, position, rank, context.businessDate());
        }
        return resolved;
    }

    /**
     * "Anyone with {@code finance.expense:approve} here".
     *
     * <p>Asked of the evaluator once per candidate, against the document's own
     * target. That is deliberately the expensive direction: the evaluator is the
     * only thing entitled to answer a permission question, and a reverse index
     * over {@code permission_grant} that agreed with it would be a second
     * permission system. Candidates are limited to the expression's domain, so
     * the count is the size of a department, not of the installation.
     */
    private List<ResolvedApprover> resolveByPermission(RoleExpression role,
            ApprovalContext context) {
        PermissionKey required = PermissionKey.parse(role.value());
        PermissionTarget target = context.target("approval routing: who holds " + required);
        List<ResolvedApprover> resolved = new ArrayList<ResolvedApprover>();
        Map<String, UserAccount> byEmployee = accountsByEmployeeId();
        Map<String, Rank> ranksById = ranksById(context.companyId());
        Set<String> seen = new LinkedHashSet<String>();
        for (Position position : positionsInDomain(role, context)) {
            UserAccount account = byEmployee.get(position.employeeId());
            if (account == null || !account.isActive() || seen.contains(account.id())) {
                continue;
            }
            PermissionPrincipal candidate = PermissionPrincipal.user(
                    account.id(), account.displayName(), account.employeeId());
            if (!permissions.check(candidate, required, target).isAllowed()) {
                continue;
            }
            if (!isEmployedOn(position.employeeId(), context.businessDate())) {
                continue;
            }
            seen.add(account.id());
            Rank rank = ranksById.get(position.rankId());
            resolved.add(new ResolvedApprover(account.id(), account.displayName(),
                    rank == null ? null : rank.labelKo(), rank == null ? 0 : rank.seniority()));
        }
        return resolved;
    }

    private void addIfEmployed(List<ResolvedApprover> resolved, Set<String> seen,
            Map<String, UserAccount> byEmployee, Position position, Rank rank, LocalDate asOf) {
        UserAccount account = byEmployee.get(position.employeeId());
        if (account == null || !account.isActive() || seen.contains(account.id())) {
            return;
        }
        if (!isEmployedOn(position.employeeId(), asOf)) {
            // A leaver keeps their position rows for the audit trail. Routing a
            // live approval to them would stall the document indefinitely.
            return;
        }
        seen.add(account.id());
        resolved.add(new ResolvedApprover(account.id(), account.displayName(),
                rank == null ? null : rank.labelKo(), rank == null ? 0 : rank.seniority()));
    }

    private boolean isEmployedOn(String employeeId, LocalDate asOf) {
        Optional<Employee> employee = employees.findById(employeeId);
        return employee.isPresent() && employee.get().isEmployedOn(asOf);
    }

    private List<Position> positionsInDomain(RoleExpression role, ApprovalContext context) {
        List<String> unitIds = domainUnitIds(role, context);
        List<Position> held = new ArrayList<Position>();
        for (String unitId : unitIds) {
            held.addAll(positions.findInUnitOn(unitId, context.businessDate()));
        }
        return held;
    }

    private List<Position> positionsInCompany(String companyId, LocalDate asOf) {
        List<Position> held = new ArrayList<Position>();
        for (OrgUnit unit : orgUnits.findByCompanyIdAndActiveTrue(companyId)) {
            held.addAll(positions.findInUnitOn(unit.id(), asOf));
        }
        return held;
    }

    private List<String> domainUnitIds(RoleExpression role, ApprovalContext context) {
        List<String> unitIds = new ArrayList<String>();
        switch (role.domain()) {
            case DRAFTER_UNIT:
                if (Texts.hasText(context.drafterOrgUnitId())) {
                    unitIds.add(context.drafterOrgUnitId());
                }
                return unitIds;
            case DRAFTER_UNIT_PARENT: {
                OrgUnit unit = unitOrNull(context.drafterOrgUnitId());
                if (unit != null && Texts.hasText(unit.parentId())) {
                    unitIds.add(unit.parentId());
                }
                return unitIds;
            }
            case DRAFTER_UNIT_SUBTREE: {
                OrgUnit unit = unitOrNull(context.drafterOrgUnitId());
                if (unit == null) {
                    return unitIds;
                }
                for (OrgUnit descendant : orgUnits.findSubtree(unit.path())) {
                    unitIds.add(descendant.id());
                }
                return unitIds;
            }
            case COMPANY:
                for (OrgUnit unit : orgUnits.findByCompanyIdAndActiveTrue(context.companyId())) {
                    unitIds.add(unit.id());
                }
                return unitIds;
            case ALL:
            default:
                for (OrgUnit unit : orgUnits.findAll()) {
                    unitIds.add(unit.id());
                }
                return unitIds;
        }
    }

    private OrgUnit unitOrNull(String orgUnitId) {
        if (!Texts.hasText(orgUnitId)) {
            return null;
        }
        Optional<OrgUnit> unit = orgUnits.findById(orgUnitId);
        return unit.isPresent() ? unit.get() : null;
    }

    private Rank rankByCode(String companyId, String code) {
        for (Rank rank : ranks.findByCompanyIdOrderBySeniorityDesc(companyId)) {
            if (rank.code().equalsIgnoreCase(code) && rank.isActive()) {
                return rank;
            }
        }
        return null;
    }

    private Map<String, Rank> ranksById(String companyId) {
        Map<String, Rank> byId = new HashMap<String, Rank>();
        for (Rank rank : ranks.findByCompanyIdOrderBySeniorityDesc(companyId)) {
            byId.put(rank.id(), rank);
        }
        return byId;
    }

    private JobFunction jobFunctionByCode(String companyId, String code) {
        for (JobFunction function : jobFunctions.findByCompanyIdAndActiveTrue(companyId)) {
            if (function.code().equalsIgnoreCase(code)) {
                return function;
            }
        }
        return null;
    }

    private Map<String, UserAccount> accountsByEmployeeId() {
        Map<String, UserAccount> byEmployee = new HashMap<String, UserAccount>();
        for (UserAccount account : accounts.findAll()) {
            if (Texts.hasText(account.employeeId())) {
                byEmployee.put(account.employeeId(), account);
            }
        }
        return byEmployee;
    }

    private static final Comparator<ResolvedApprover> SENIOR_FIRST =
            new Comparator<ResolvedApprover>() {
                @Override
                public int compare(ResolvedApprover left, ResolvedApprover right) {
                    int bySeniority = Integer.compare(right.seniority(), left.seniority());
                    if (bySeniority != 0) {
                        return bySeniority;
                    }
                    String leftName = left.displayName() == null ? "" : left.displayName();
                    String rightName = right.displayName() == null ? "" : right.displayName();
                    int byName = leftName.compareTo(rightName);
                    return byName != 0 ? byName : left.accountId().compareTo(right.accountId());
                }
            };
}
