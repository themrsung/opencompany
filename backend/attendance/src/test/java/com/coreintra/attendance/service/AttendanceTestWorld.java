package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.attendance.repository.AttendanceRecordRepository;
import com.coreintra.attendance.repository.AttendanceStatusTypeRepository;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.permission.PrincipalOrgState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A small company with attendance, for the service tests.
 *
 * <p>The permission evaluator is the real {@link DefaultPermissionEvaluator}
 * over an in-memory org chart, wrapped in a counter — several tests assert not
 * only that a call is authorised but that authorising it costs one check rather
 * than one per person on the board.
 *
 * <p>The repositories count their calls for the same reason: the who's-in view
 * claims a fixed number of queries whatever the headcount, and a claim like that
 * is worth holding to.
 */
final class AttendanceTestWorld {

    static final String COMPANY = "co-1";
    static final LocalDate DAY = LocalDate.of(2026, 8, 30);

    final Records records = new Records();
    final StatusTypes statusTypes = new StatusTypes();
    final Positions positions = new Positions();
    final Units units = new Units();
    final Grants grants = new Grants();
    final CountingEvaluator permissions;
    final Ledgers ledgers = new Ledgers();
    final Policies policies = new Policies();

    AttendanceSettings settings = AttendanceSettings.fixed(OverlapPolicy.CLOSE_PREVIOUS);

    AttendanceTestWorld() {
        this.permissions = new CountingEvaluator(new DefaultPermissionEvaluator(
                new TestOrgDirectory(positions, units), grants));
    }

    AttendanceStatusService statusService() {
        return new AttendanceStatusService(statusTypes, permissions);
    }

    AttendanceRecordService recordService() {
        return new AttendanceRecordService(records, statusService(), settings, permissions,
                new EmployeeTargets(positions));
    }

    WhosInService whosInService() {
        return new WhosInService(records, statusService(), positions, units, permissions);
    }

    LeaveService leaveService() {
        return new LeaveService(ledgers, policies, permissions, new EmployeeTargets(positions));
    }

    // ------------------------------------------------------------------

    OrgUnit unit(String code) {
        OrgUnit unit = new OrgUnit("unit-" + code, COMPANY, code, code);
        units.save(unit);
        return unit;
    }

    /** An employee with a position. Attendance principals carry the employee id. */
    PermissionPrincipal person(String name, OrgUnit unit) {
        Employee employee = new Employee("emp-" + name, COMPANY, name);
        employee.setHiredOn(LocalDate.of(2020, 1, 1));
        Rank rank = new Rank("rank-SAWON", COMPANY, "SAWON", "사원", 10);
        Position position = new Position("pos-" + name, employee.id(), unit.id(), rank.id(),
                LocalDate.of(2020, 1, 1));
        position.setPrimary(true);
        positions.save(position);
        return PermissionPrincipal.user("acc-" + name, name, employee.id());
    }

    AttendanceStatusType status(String code, StatusBehaviour behaviour) {
        AttendanceStatusType type = new AttendanceStatusType("st-" + code, COMPANY, code, code,
                behaviour);
        statusTypes.save(type);
        return type;
    }

    static StatusBehaviour working() {
        return StatusBehaviour.builder().countsAsWorking(true).visibleToPeers(true).build();
    }

    static StatusBehaviour privateStatus() {
        return StatusBehaviour.builder().visibleToPeers(false).build();
    }

    // ------------------------------------------------------------------

    static final class Records extends InMemoryRepository<AttendanceRecord, String>
            implements AttendanceRecordRepository {

        int queries;

        @Override
        String idOf(AttendanceRecord entity) {
            return entity.id();
        }

        @Override
        public List<AttendanceRecord> findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
                String employeeId, LocalDate businessDate) {
            queries++;
            List<AttendanceRecord> found = new ArrayList<AttendanceRecord>();
            for (AttendanceRecord record : rows.values()) {
                if (record.employeeId().equals(employeeId)
                        && record.businessDate().equals(businessDate)) {
                    found.add(record);
                }
            }
            found.sort(byStart());
            return found;
        }

        @Override
        public List<AttendanceRecord> findDayForEmployees(LocalDate businessDate,
                Collection<String> employeeIds) {
            queries++;
            List<AttendanceRecord> found = new ArrayList<AttendanceRecord>();
            for (AttendanceRecord record : rows.values()) {
                if (record.businessDate().equals(businessDate)
                        && employeeIds.contains(record.employeeId())) {
                    found.add(record);
                }
            }
            found.sort(byStart());
            return found;
        }

        @Override
        public List<AttendanceRecord> findByBusinessDateAndEndedOffsetSecondsIsNull(
                LocalDate businessDate) {
            queries++;
            List<AttendanceRecord> found = new ArrayList<AttendanceRecord>();
            for (AttendanceRecord record : rows.values()) {
                if (record.businessDate().equals(businessDate) && record.isOpen()) {
                    found.add(record);
                }
            }
            return found;
        }

        private static Comparator<AttendanceRecord> byStart() {
            return new Comparator<AttendanceRecord>() {
                @Override
                public int compare(AttendanceRecord left, AttendanceRecord right) {
                    return Integer.compare(left.interval().startedAt().offsetSeconds(),
                            right.interval().startedAt().offsetSeconds());
                }
            };
        }
    }

    static final class StatusTypes extends InMemoryRepository<AttendanceStatusType, String>
            implements AttendanceStatusTypeRepository {

        int queries;

        @Override
        String idOf(AttendanceStatusType entity) {
            return entity.id();
        }

        @Override
        public List<AttendanceStatusType> findByCompanyIdAndActiveTrueOrderBySortOrderAsc(
                String companyId) {
            queries++;
            List<AttendanceStatusType> found = new ArrayList<AttendanceStatusType>();
            for (AttendanceStatusType status : rows.values()) {
                if (status.companyId().equals(companyId) && status.isActive()) {
                    found.add(status);
                }
            }
            found.sort(new Comparator<AttendanceStatusType>() {
                @Override
                public int compare(AttendanceStatusType left, AttendanceStatusType right) {
                    return Integer.compare(left.sortOrder(), right.sortOrder());
                }
            });
            return found;
        }

        @Override
        public Optional<AttendanceStatusType> findByCompanyIdAndCode(String companyId,
                String code) {
            queries++;
            for (AttendanceStatusType status : rows.values()) {
                if (status.companyId().equals(companyId) && status.code().equals(code)) {
                    return Optional.of(status);
                }
            }
            return Optional.empty();
        }
    }

    static final class Positions extends InMemoryRepository<Position, String>
            implements PositionRepository {

        int queries;

        @Override
        String idOf(Position entity) {
            return entity.id();
        }

        @Override
        public List<Position> findActiveOn(String employeeId, LocalDate asOf) {
            List<Position> found = new ArrayList<Position>();
            for (Position position : rows.values()) {
                if (position.employeeId().equals(employeeId) && position.isActiveOn(asOf)) {
                    found.add(position);
                }
            }
            return found;
        }

        @Override
        public List<Position> findInUnitOn(String orgUnitId, LocalDate asOf) {
            queries++;
            List<Position> found = new ArrayList<Position>();
            for (Position position : rows.values()) {
                if (position.orgUnitId().equals(orgUnitId) && position.isActiveOn(asOf)) {
                    found.add(position);
                }
            }
            return found;
        }

        @Override
        public List<String> findJobFunctionIds(Collection<String> positionIds) {
            return Immutables.<String>listOf();
        }
    }

    static final class Units extends InMemoryRepository<OrgUnit, String>
            implements OrgUnitRepository {

        @Override
        String idOf(OrgUnit entity) {
            return entity.id();
        }

        @Override
        public List<OrgUnit> findByCompanyIdAndActiveTrue(String companyId) {
            List<OrgUnit> found = new ArrayList<OrgUnit>();
            for (OrgUnit unit : rows.values()) {
                if (unit.companyId().equals(companyId) && unit.isActive()) {
                    found.add(unit);
                }
            }
            return found;
        }

        @Override
        public List<OrgUnit> findSubtree(String path) {
            List<OrgUnit> found = new ArrayList<OrgUnit>();
            for (OrgUnit unit : rows.values()) {
                if (unit.path().startsWith(path)) {
                    found.add(unit);
                }
            }
            return found;
        }
    }

    /** Deny by default; a test grants exactly what it means to, at a scope. */
    static final class Grants implements GrantDirectory {

        private final Map<String, List<PermissionGrant>> byAccount =
                new LinkedHashMap<String, List<PermissionGrant>>();

        void grant(PermissionPrincipal principal, PermissionKey key, PermissionScope scope) {
            List<PermissionGrant> held = byAccount.get(principal.accountId());
            if (held == null) {
                held = new ArrayList<PermissionGrant>();
                byAccount.put(principal.accountId(), held);
            }
            held.add(PermissionGrant.allow(key, scope, GrantSource.USER_ACCOUNT,
                    principal.accountId(), "granted directly in a test"));
        }

        @Override
        public List<PermissionGrant> grantsFor(PermissionPrincipal principal,
                PrincipalOrgState orgState) {
            List<PermissionGrant> held = byAccount.get(principal.accountId());
            return held == null ? Immutables.<PermissionGrant>listOf() : held;
        }
    }

    static final class TestOrgDirectory implements OrgDirectory {

        private final Positions positions;
        private final Units units;

        TestOrgDirectory(Positions positions, Units units) {
            this.positions = positions;
            this.units = units;
        }

        @Override
        public PrincipalOrgState resolve(PermissionPrincipal principal, LocalDate asOf) {
            if (principal.employeeId() == null) {
                return PrincipalOrgState.none(null);
            }
            Set<String> unitIds = new LinkedHashSet<String>();
            Set<String> companyIds = new LinkedHashSet<String>();
            Set<String> rankIds = new LinkedHashSet<String>();
            for (Position position : positions.findActiveOn(principal.employeeId(), asOf)) {
                unitIds.add(position.orgUnitId());
                rankIds.add(position.rankId());
                Optional<OrgUnit> unit = units.findById(position.orgUnitId());
                if (unit.isPresent()) {
                    companyIds.add(unit.get().companyId());
                }
            }
            return new PrincipalOrgState(principal.employeeId(), unitIds, companyIds, rankIds,
                    Immutables.<String>setOf());
        }

        @Override
        public boolean isInSubtree(String ancestorId, String candidateDescendantId,
                LocalDate asOf) {
            Optional<OrgUnit> ancestor = units.findById(ancestorId);
            Optional<OrgUnit> candidate = units.findById(candidateDescendantId);
            return ancestor.isPresent() && candidate.isPresent()
                    && ancestor.get().contains(candidate.get());
        }
    }

    /** The real evaluator, counted. */
    static final class CountingEvaluator implements PermissionEvaluator {

        private final PermissionEvaluator delegate;
        int checks;

        CountingEvaluator(PermissionEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public PermissionDecision check(PermissionPrincipal principal, PermissionKey key,
                PermissionTarget target) {
            checks++;
            return delegate.check(principal, key, target);
        }

        @Override
        public PermissionDecision check(PermissionPrincipal principal, String resource,
                String action, PermissionTarget target) {
            checks++;
            return delegate.check(principal, resource, action, target);
        }

        @Override
        public EffectivePermissions effectivePermissions(PermissionPrincipal principal,
                LocalDate asOfBusinessDate) {
            return delegate.effectivePermissions(principal, asOfBusinessDate);
        }
    }

    static final class Policies implements LeavePolicyStore {

        final Map<String, LeaveAccrualPolicy> byId =
                new LinkedHashMap<String, LeaveAccrualPolicy>();
        final Map<String, String> idByCode = new LinkedHashMap<String, String>();

        LeaveAccrualPolicy add(String code, LeaveAccrualPolicy policy) {
            byId.put(policy.id(), policy);
            idByCode.put(code, policy.id());
            return policy;
        }

        @Override
        public List<LeaveAccrualPolicy> findActive(String companyId) {
            return new ArrayList<LeaveAccrualPolicy>(byId.values());
        }

        @Override
        public Optional<LeaveAccrualPolicy> findById(String policyId) {
            return Optional.ofNullable(byId.get(policyId));
        }

        @Override
        public Optional<LeaveAccrualPolicy> findByCode(String companyId, String code) {
            String id = idByCode.get(code);
            return id == null ? Optional.<LeaveAccrualPolicy>empty() : findById(id);
        }
    }

    /** Append-only in memory, exactly as the table is on disk. */
    static final class Ledgers implements LeaveLedgerStore {

        final List<Row> appended = new ArrayList<Row>();

        static final class Row {
            final String employeeId;
            final String policyId;
            final LeaveLedger.Transaction transaction;

            Row(String employeeId, String policyId, LeaveLedger.Transaction transaction) {
                this.employeeId = employeeId;
                this.policyId = policyId;
                this.transaction = transaction;
            }
        }

        @Override
        public List<LeaveLedger.Transaction> transactionsFor(String employeeId, String policyId) {
            List<LeaveLedger.Transaction> found = new ArrayList<LeaveLedger.Transaction>();
            for (Row row : appended) {
                if (row.employeeId.equals(employeeId) && row.policyId.equals(policyId)) {
                    found.add(row.transaction);
                }
            }
            return found;
        }

        @Override
        public List<LeaveLedger.Transaction> findBySourceDocumentId(String sourceDocumentId) {
            List<LeaveLedger.Transaction> found = new ArrayList<LeaveLedger.Transaction>();
            for (Row row : appended) {
                if (sourceDocumentId.equals(row.transaction.sourceDocumentId())) {
                    found.add(row.transaction);
                }
            }
            return found;
        }

        @Override
        public void append(String employeeId, String policyId,
                LeaveLedger.Transaction transaction) {
            appended.add(new Row(employeeId, policyId, transaction));
        }

        int size() {
            return appended.size();
        }
    }
}
