package com.coreintra.core.service;

import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PrincipalOrgState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The whole org, in memory: every repository this package declares, and the two
 * directories the permission engine reads, over one store.
 *
 * <h2>Why not the permission module's InMemoryOrg</h2>
 *
 * <p>{@code com.coreintra.core.permission.InMemoryOrg} is the right idea and the
 * wrong reach: it is package-private in its own package, it keys positions by
 * account id, and - decisively - it holds its own store. These tests need the
 * opposite arrangement. A service writes a position through a repository and the
 * evaluator has to see <em>that</em> write, because "a promotion does not change
 * last quarter's answer" is a claim about the two halves agreeing. Two stores
 * would let the test pass while the system it describes was broken. That file
 * belongs to another agent, so it is left alone rather than widened.
 *
 * <p>The directory half deliberately mirrors {@code JpaOrgDirectory} and {@code
 * JpaGrantDirectory} rather than inventing rules: a position counts on a date,
 * subtree membership is a prefix test on the materialised path, and a grant
 * reaches a principal when its source is one of their ranks, 직무, units, or the
 * account itself.
 *
 * <p>Each repository is a separate object because the interfaces are separate:
 * {@code findById} cannot return a company and a unit from the same class.
 */
final class InMemoryOrgBook implements OrgDirectory, GrantDirectory {

    private final Map<String, Company> companyRows = new LinkedHashMap<String, Company>();
    private final Map<String, OrgUnit> unitRows = new LinkedHashMap<String, OrgUnit>();
    private final Map<String, Rank> rankRows = new LinkedHashMap<String, Rank>();
    private final Map<String, JobFunction> jobFunctionRows = new LinkedHashMap<String, JobFunction>();
    private final Map<String, Employee> employeeRows = new LinkedHashMap<String, Employee>();
    private final Map<String, Position> positionRows = new LinkedHashMap<String, Position>();
    private final Map<String, UserAccount> accountRows = new LinkedHashMap<String, UserAccount>();
    private final Map<String, PermissionGrantRow> grantRows = new LinkedHashMap<String, PermissionGrantRow>();
    private final Map<String, Set<String>> positionFunctions = new LinkedHashMap<String, Set<String>>();

    private final CompanyStore companies = new CompanyStore();
    private final OrgUnitStore units = new OrgUnitStore();
    private final RankStore ranks = new RankStore();
    private final JobFunctionStore jobFunctions = new JobFunctionStore();
    private final EmployeeStore employees = new EmployeeStore();
    private final PositionStore positions = new PositionStore();
    private final AccountStore accounts = new AccountStore();
    private final GrantStore grants = new GrantStore();

    CompanyCatalogRepository companies() {
        return companies;
    }

    OrgUnitCatalogRepository units() {
        return units;
    }

    RankCatalogRepository ranks() {
        return ranks;
    }

    JobFunctionCatalogRepository jobFunctions() {
        return jobFunctions;
    }

    EmployeeCatalogRepository employees() {
        return employees;
    }

    PositionAssignmentRepository positions() {
        return positions;
    }

    UserAccountCatalogRepository accounts() {
        return accounts;
    }

    GrantWriteRepository grants() {
        return grants;
    }

    /** Accounts are written by the auth module, so the fixture puts them in directly. */
    InMemoryOrgBook account(UserAccount account) {
        accountRows.put(account.id(), account);
        return this;
    }

    /** Seeds a grant the way an installer would: straight in, not through the gate. */
    InMemoryOrgBook seedGrant(PermissionGrantRow row) {
        grantRows.put(row.id(), row);
        return this;
    }

    /** Rows as stored, for assertions about what a write actually left behind. */
    List<Position> allPositions() {
        return new ArrayList<Position>(positionRows.values());
    }

    List<Rank> allRanks() {
        return new ArrayList<Rank>(rankRows.values());
    }

    Set<String> functionsOf(String positionId) {
        Set<String> linked = positionFunctions.get(positionId);
        if (linked == null) {
            linked = new LinkedHashSet<String>();
            positionFunctions.put(positionId, linked);
        }
        return linked;
    }

    // ------------------------------------------------------------- directories

    @Override
    public PrincipalOrgState resolve(PermissionPrincipal principal, LocalDate asOf) {
        String employeeId = principal.employeeId();
        if (employeeId == null) {
            return PrincipalOrgState.none(null);
        }
        Set<String> unitIds = new HashSet<String>();
        Set<String> companyIds = new HashSet<String>();
        Set<String> rankIds = new HashSet<String>();
        Set<String> functionIds = new HashSet<String>();
        for (Position position : positionRows.values()) {
            if (!position.employeeId().equals(employeeId) || !position.isActiveOn(asOf)) {
                continue;
            }
            unitIds.add(position.orgUnitId());
            rankIds.add(position.rankId());
            functionIds.addAll(functionsOf(position.id()));
            OrgUnit unit = unitRows.get(position.orgUnitId());
            if (unit != null) {
                companyIds.add(unit.companyId());
            }
        }
        return new PrincipalOrgState(employeeId, unitIds, companyIds, rankIds, functionIds);
    }

    @Override
    public boolean isInSubtree(String ancestorId, String candidateDescendantId, LocalDate asOf) {
        if (ancestorId == null || candidateDescendantId == null) {
            return false;
        }
        if (ancestorId.equals(candidateDescendantId)) {
            return true;
        }
        OrgUnit ancestor = unitRows.get(ancestorId);
        OrgUnit candidate = unitRows.get(candidateDescendantId);
        if (ancestor == null || candidate == null) {
            return false;
        }
        return candidate.path().startsWith(ancestor.path());
    }

    @Override
    public List<PermissionGrant> grantsFor(PermissionPrincipal principal, PrincipalOrgState orgState) {
        List<PermissionGrant> reaching = new ArrayList<PermissionGrant>();
        for (PermissionGrantRow row : grantRows.values()) {
            if (reaches(row, principal, orgState)) {
                reaching.add(row.toDomain(label(row)));
            }
        }
        return reaching;
    }

    private boolean reaches(PermissionGrantRow row, PermissionPrincipal principal, PrincipalOrgState orgState) {
        if (row.isRevoked()) {
            // Mirrors the partial index the real query relies on. Without this
            // the double would keep answering "allowed" after a revocation and
            // the test would pass against behaviour production does not have.
            return false;
        }
        switch (row.source()) {
            case RANK:
                return orgState.rankIds().contains(row.sourceId());
            case JOB_FUNCTION:
                return orgState.jobFunctionIds().contains(row.sourceId());
            case ORG_UNIT:
                return orgState.orgUnitIds().contains(row.sourceId());
            case USER_ACCOUNT:
                return row.sourceId().equals(principal.accountId());
            default:
                return false;
        }
    }

    private String label(PermissionGrantRow row) {
        switch (row.source()) {
            case RANK: {
                Rank rank = rankRows.get(row.sourceId());
                return rank == null ? row.sourceId() : rank.labelKo();
            }
            case JOB_FUNCTION: {
                JobFunction jobFunction = jobFunctionRows.get(row.sourceId());
                return jobFunction == null ? row.sourceId() : jobFunction.labelKo();
            }
            case ORG_UNIT: {
                OrgUnit unit = unitRows.get(row.sourceId());
                return unit == null ? row.sourceId() : unit.nameKo();
            }
            case USER_ACCOUNT:
                return "granted directly to this account";
            default:
                return row.sourceId();
        }
    }

    // ------------------------------------------------------------ repositories

    private final class CompanyStore implements CompanyCatalogRepository {

        @Override
        public <S extends Company> S save(S company) {
            companyRows.put(company.id(), company);
            return company;
        }

        @Override
        public Optional<Company> findById(String id) {
            return Optional.ofNullable(companyRows.get(id));
        }

        @Override
        public List<Company> findAllByOrderByCodeAsc() {
            List<Company> all = new ArrayList<Company>(companyRows.values());
            Collections.sort(all, new Comparator<Company>() {
                @Override
                public int compare(Company left, Company right) {
                    return left.code().compareTo(right.code());
                }
            });
            return all;
        }

        @Override
        public Optional<Company> findByCode(String code) {
            for (Company company : companyRows.values()) {
                if (company.code().equals(code)) {
                    return Optional.of(company);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<Company> findByParentCompanyId(String parentCompanyId) {
            List<Company> children = new ArrayList<Company>();
            for (Company company : companyRows.values()) {
                if (parentCompanyId.equals(company.parentCompanyId())) {
                    children.add(company);
                }
            }
            return children;
        }
    }

    private final class OrgUnitStore implements OrgUnitCatalogRepository {

        @Override
        public <S extends OrgUnit> S save(S unit) {
            unitRows.put(unit.id(), unit);
            return unit;
        }

        @Override
        public <S extends OrgUnit> List<S> saveAll(Iterable<S> toSave) {
            List<S> saved = new ArrayList<S>();
            for (S unit : toSave) {
                unitRows.put(unit.id(), unit);
                saved.add(unit);
            }
            return saved;
        }

        @Override
        public Optional<OrgUnit> findById(String id) {
            return Optional.ofNullable(unitRows.get(id));
        }

        @Override
        public List<OrgUnit> findByCompanyIdOrderByPathAsc(String companyId) {
            List<OrgUnit> owned = new ArrayList<OrgUnit>();
            for (OrgUnit unit : unitRows.values()) {
                if (unit.companyId().equals(companyId)) {
                    owned.add(unit);
                }
            }
            Collections.sort(owned, new Comparator<OrgUnit>() {
                @Override
                public int compare(OrgUnit left, OrgUnit right) {
                    return left.path().compareTo(right.path());
                }
            });
            return owned;
        }
    }

    private final class RankStore implements RankCatalogRepository {

        @Override
        public <S extends Rank> S save(S rank) {
            rankRows.put(rank.id(), rank);
            return rank;
        }

        @Override
        public <S extends Rank> List<S> saveAll(Iterable<S> toSave) {
            List<S> saved = new ArrayList<S>();
            for (S rank : toSave) {
                rankRows.put(rank.id(), rank);
                saved.add(rank);
            }
            return saved;
        }

        @Override
        public Optional<Rank> findById(String id) {
            return Optional.ofNullable(rankRows.get(id));
        }

        @Override
        public List<Rank> findByCompanyIdOrderBySeniorityDesc(String companyId) {
            List<Rank> ladder = new ArrayList<Rank>();
            for (Rank rank : rankRows.values()) {
                if (rank.companyId().equals(companyId)) {
                    ladder.add(rank);
                }
            }
            Collections.sort(ladder, new Comparator<Rank>() {
                @Override
                public int compare(Rank left, Rank right) {
                    return right.seniority() - left.seniority();
                }
            });
            return ladder;
        }
    }

    private final class JobFunctionStore implements JobFunctionCatalogRepository {

        @Override
        public <S extends JobFunction> S save(S jobFunction) {
            jobFunctionRows.put(jobFunction.id(), jobFunction);
            return jobFunction;
        }

        @Override
        public Optional<JobFunction> findById(String id) {
            return Optional.ofNullable(jobFunctionRows.get(id));
        }

        @Override
        public List<JobFunction> findByCompanyIdOrderByCodeAsc(String companyId) {
            List<JobFunction> owned = new ArrayList<JobFunction>();
            for (JobFunction jobFunction : jobFunctionRows.values()) {
                if (jobFunction.companyId().equals(companyId)) {
                    owned.add(jobFunction);
                }
            }
            Collections.sort(owned, new Comparator<JobFunction>() {
                @Override
                public int compare(JobFunction left, JobFunction right) {
                    return left.code().compareTo(right.code());
                }
            });
            return owned;
        }
    }

    private final class EmployeeStore implements EmployeeCatalogRepository {

        @Override
        public <S extends Employee> S save(S employee) {
            employeeRows.put(employee.id(), employee);
            return employee;
        }

        @Override
        public Optional<Employee> findById(String id) {
            return Optional.ofNullable(employeeRows.get(id));
        }

        @Override
        public List<Employee> findByCompanyIdOrderByNameKoAsc(String companyId) {
            List<Employee> owned = new ArrayList<Employee>();
            for (Employee employee : employeeRows.values()) {
                if (employee.companyId().equals(companyId)) {
                    owned.add(employee);
                }
            }
            Collections.sort(owned, new Comparator<Employee>() {
                @Override
                public int compare(Employee left, Employee right) {
                    return left.nameKo().compareTo(right.nameKo());
                }
            });
            return owned;
        }

        @Override
        public Optional<Employee> findByCompanyIdAndEmployeeNumber(String companyId, String employeeNumber) {
            for (Employee employee : employeeRows.values()) {
                if (employee.companyId().equals(companyId) && employeeNumber.equals(employee.employeeNumber())) {
                    return Optional.of(employee);
                }
            }
            return Optional.empty();
        }
    }

    private final class PositionStore implements PositionAssignmentRepository {

        @Override
        public <S extends Position> S save(S position) {
            positionRows.put(position.id(), position);
            return position;
        }

        @Override
        public Optional<Position> findById(String id) {
            return Optional.ofNullable(positionRows.get(id));
        }

        @Override
        public List<Position> findByEmployeeIdOrderByEffectiveFromAsc(String employeeId) {
            List<Position> held = new ArrayList<Position>();
            for (Position position : positionRows.values()) {
                if (position.employeeId().equals(employeeId)) {
                    held.add(position);
                }
            }
            Collections.sort(held, new Comparator<Position>() {
                @Override
                public int compare(Position left, Position right) {
                    return left.effectiveFrom().compareTo(right.effectiveFrom());
                }
            });
            return held;
        }

        @Override
        public List<Position> findLiveInUnit(String orgUnitId, LocalDate asOf) {
            List<Position> live = new ArrayList<Position>();
            for (Position position : positionRows.values()) {
                if (position.orgUnitId().equals(orgUnitId) && position.isActiveOn(asOf)) {
                    live.add(position);
                }
            }
            return live;
        }

        @Override
        public List<Position> findLiveWithRank(String rankId, LocalDate asOf) {
            List<Position> live = new ArrayList<Position>();
            for (Position position : positionRows.values()) {
                if (position.rankId().equals(rankId) && position.isActiveOn(asOf)) {
                    live.add(position);
                }
            }
            return live;
        }

        @Override
        public long countLiveWithJobFunction(String jobFunctionId, LocalDate asOf) {
            long performing = 0;
            for (Position position : positionRows.values()) {
                if (position.isActiveOn(asOf) && functionsOf(position.id()).contains(jobFunctionId)) {
                    performing++;
                }
            }
            return performing;
        }

        @Override
        public void linkJobFunction(String positionId, String jobFunctionId) {
            functionsOf(positionId).add(jobFunctionId);
        }
    }

    private final class AccountStore implements UserAccountCatalogRepository {

        @Override
        public Optional<UserAccount> findById(String id) {
            return Optional.ofNullable(accountRows.get(id));
        }

        @Override
        public Optional<UserAccount> findByEmployeeId(String employeeId) {
            for (UserAccount account : accountRows.values()) {
                if (employeeId.equals(account.employeeId())) {
                    return Optional.of(account);
                }
            }
            return Optional.empty();
        }
    }

    private final class GrantStore implements GrantWriteRepository {

        @Override
        public <S extends PermissionGrantRow> S save(S row) {
            grantRows.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<PermissionGrantRow> findById(String id) {
            return Optional.ofNullable(grantRows.get(id));
        }

        @Override
        public List<PermissionGrantRow> findBySourceAndSourceId(GrantSource source, String sourceId) {
            List<PermissionGrantRow> attached = new ArrayList<PermissionGrantRow>();
            for (PermissionGrantRow row : grantRows.values()) {
                if (row.source() == source && row.sourceId().equals(sourceId)) {
                    attached.add(row);
                }
            }
            return attached;
        }
    }
}
