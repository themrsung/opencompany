package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.approval.notify.Notifier;
import com.coreintra.approval.repository.ApprovalActionRepository;
import com.coreintra.approval.repository.ApprovalDocumentRepository;
import com.coreintra.approval.repository.ApprovalStepApproverRepository;
import com.coreintra.approval.repository.ApprovalStepRepository;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.compat.Immutables;
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
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
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
import org.springframework.data.domain.Pageable;

/**
 * A small, real company the service tests can act on.
 *
 * <p>Everything is in memory and everything is genuine: a company with a 부서
 * tree, ranks including a representative one, employees with dated positions,
 * and the four approval tables. The services under test are the production
 * ones, wired to these.
 *
 * <p>The permission evaluator is the one test double here that stands in for
 * something with real logic, and it is written to be <em>strict</em>: it denies
 * by default and only allows what a test grants. A permissive stub would make
 * every "this is refused without permission" assertion pass for the wrong
 * reason.
 */
final class ApprovalTestWorld {

    static final String COMPANY = "co-1";
    static final LocalDate DAY = LocalDate.of(2026, 8, 30);

    final Documents documents = new Documents();
    final Steps steps = new Steps();
    final Approvers approvers = new Approvers();
    final Actions actions = new Actions();
    final Accounts accounts = new Accounts();
    final Positions positions = new Positions();
    final Units units = new Units();
    final Ranks ranks = new Ranks();
    final Functions functions = new Functions();
    final Employees employees = new Employees();
    final Grants grants = new Grants();
    final PermissionEvaluator permissions;
    final RecordingNotifier notifier = new RecordingNotifier();
    final Templates templates = new Templates();
    final FixedRepresentation representation = new FixedRepresentation();
    final RulesStore rulesStore = new RulesStore();

    /**
     * Wires the <em>real</em> evaluator over an in-memory org chart.
     *
     * <p>Not a stub that answers yes. Scope semantics — SELF reaching only your
     * own rows, COMPANY needing a position in that company, deny beating allow —
     * are the production ones, so a test that grants the wrong scope fails here
     * exactly as it would in an installation.
     */
    ApprovalTestWorld() {
        this.permissions = new DefaultPermissionEvaluator(
                new TestOrgDirectory(positions, units), grants);
    }

    ApproverDirectory directory() {
        return new ApproverDirectory(units, positions, ranks, functions, employees, accounts,
                permissions);
    }

    ApprovalLineTemplateService templateService() {
        return new ApprovalLineTemplateService(templates, units);
    }

    ApprovalDocumentService documentService() {
        return new ApprovalDocumentService(documents, steps, approvers, actions, accounts,
                positions, directory(), templateService(), representation, permissions,
                Immutables.<Notifier>listOf(notifier));
    }

    /** No absence directory wired, which is the default an installation gets. */
    ApprovalActionService actionService(ApprovalOutcomeListener... listeners) {
        return new ApprovalActionService(documentService(), documents, steps, actions, accounts,
                permissions, Immutables.<Notifier>listOf(notifier),
                Immutables.listOfArray(listeners), Immutables.<AbsenceDirectory>listOf());
    }

    /** With one, so 대결 can be checked against a real absence. */
    ApprovalActionService actionServiceKnowingAbsences(AbsenceDirectory absences) {
        return new ApprovalActionService(documentService(), documents, steps, actions, accounts,
                permissions, Immutables.<Notifier>listOf(notifier),
                Immutables.<ApprovalOutcomeListener>listOf(), Immutables.listOf(absences));
    }

    EmploymentRulesService employmentRulesService() {
        return new EmploymentRulesService(rulesStore, documents, steps, approvers, actions,
                representation, directory(), permissions, documentService());
    }

    // ------------------------------------------------------------------
    // Building the company
    // ------------------------------------------------------------------

    OrgUnit unit(String code, OrgUnit parent) {
        OrgUnit unit = new OrgUnit("unit-" + code, COMPANY, code, code);
        unit.attachTo(parent);
        units.save(unit);
        return unit;
    }

    Rank rank(String code, int seniority, boolean representative) {
        Rank rank = new Rank("rank-" + code, COMPANY, code, code, seniority);
        rank.setRepresentative(representative);
        ranks.save(rank);
        return rank;
    }

    /** An employee with an account and a dated position. Returns the account id. */
    String person(String name, OrgUnit unit, Rank rank, LocalDate hiredOn) {
        Employee employee = new Employee("emp-" + name, COMPANY, name);
        employee.setHiredOn(hiredOn);
        employees.save(employee);

        UserAccount account = new UserAccount("acc-" + name, name, name,
                UserAccount.AccountKind.USER);
        account.linkToEmployee(employee.id());
        accounts.save(account);

        Position position = new Position("pos-" + name, employee.id(), unit.id(), rank.id(),
                hiredOn);
        position.setPrimary(true);
        positions.save(position);
        return account.id();
    }

    PermissionPrincipal principal(String accountId) {
        UserAccount account = accounts.rows.get(accountId);
        return PermissionPrincipal.user(accountId, account.displayName(), account.employeeId());
    }

    /** A master account with an employee, for the "no path bypasses this" tests. */
    PermissionPrincipal master(String accountId) {
        UserAccount account = accounts.rows.get(accountId);
        return PermissionPrincipal.master(accountId, account.displayName(), account.employeeId());
    }

    // ------------------------------------------------------------------
    // Doubles
    // ------------------------------------------------------------------

    static final class Documents extends InMemoryRepository<ApprovalDocumentEntity, String>
            implements ApprovalDocumentRepository {

        @Override
        String idOf(ApprovalDocumentEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalDocumentEntity> findInbox(String accountId,
                Collection<ApprovalState> activeStates, Pageable pageable) {
            throw new UnsupportedOperationException(
                    "the inbox is served by ApprovalInboxService's single query, not this method");
        }

        @Override
        public List<ApprovalDocumentEntity> findByDrafterAccountIdOrderByCreatedAtDesc(
                String drafterAccountId) {
            List<ApprovalDocumentEntity> found = new ArrayList<ApprovalDocumentEntity>();
            for (ApprovalDocumentEntity document : rows.values()) {
                if (document.drafterAccountId().equals(drafterAccountId)) {
                    found.add(document);
                }
            }
            found.sort(new Comparator<ApprovalDocumentEntity>() {
                @Override
                public int compare(ApprovalDocumentEntity left, ApprovalDocumentEntity right) {
                    return right.createdAt().compareTo(left.createdAt());
                }
            });
            return found;
        }

        @Override
        public List<ApprovalDocumentEntity> findByCompanyIdAndState(String companyId,
                ApprovalState state) {
            List<ApprovalDocumentEntity> found = new ArrayList<ApprovalDocumentEntity>();
            for (ApprovalDocumentEntity document : rows.values()) {
                if (document.companyId().equals(companyId) && document.state() == state) {
                    found.add(document);
                }
            }
            return found;
        }
    }

    static final class Steps extends InMemoryRepository<ApprovalStepEntity, String>
            implements ApprovalStepRepository {

        @Override
        String idOf(ApprovalStepEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalStepEntity> findByDocumentIdOrderByPositionAsc(String documentId) {
            List<ApprovalStepEntity> found = new ArrayList<ApprovalStepEntity>();
            for (ApprovalStepEntity step : rows.values()) {
                if (step.documentId().equals(documentId)) {
                    found.add(step);
                }
            }
            found.sort(new Comparator<ApprovalStepEntity>() {
                @Override
                public int compare(ApprovalStepEntity left, ApprovalStepEntity right) {
                    return Integer.compare(left.position(), right.position());
                }
            });
            return found;
        }
    }

    static final class Approvers
            extends InMemoryRepository<ApprovalStepApproverEntity, ApprovalStepApproverEntity.Key>
            implements ApprovalStepApproverRepository {

        @Override
        ApprovalStepApproverEntity.Key idOf(ApprovalStepApproverEntity entity) {
            return new ApprovalStepApproverEntity.Key(entity.stepId(), entity.accountId());
        }

        @Override
        public List<ApprovalStepApproverEntity> findByStepIdIn(Collection<String> stepIds) {
            List<ApprovalStepApproverEntity> found = new ArrayList<ApprovalStepApproverEntity>();
            for (ApprovalStepApproverEntity approver : rows.values()) {
                if (stepIds.contains(approver.stepId())) {
                    found.add(approver);
                }
            }
            return found;
        }
    }

    static final class Actions extends InMemoryRepository<ApprovalActionEntity, String>
            implements ApprovalActionRepository {

        @Override
        String idOf(ApprovalActionEntity entity) {
            return entity.id();
        }

        @Override
        public List<ApprovalActionEntity>
                findByStepIdInOrderByActedAtBusinessDateAscActedAtOffsetSecondsAsc(
                        Collection<String> stepIds) {
            List<ApprovalActionEntity> found = new ArrayList<ApprovalActionEntity>();
            for (ApprovalActionEntity action : rows.values()) {
                if (stepIds.contains(action.stepId())) {
                    found.add(action);
                }
            }
            found.sort(new Comparator<ApprovalActionEntity>() {
                @Override
                public int compare(ApprovalActionEntity left, ApprovalActionEntity right) {
                    int byDate = left.actedAt().businessDate()
                            .compareTo(right.actedAt().businessDate());
                    return byDate != 0 ? byDate : Integer.compare(
                            left.actedAt().offsetSeconds(), right.actedAt().offsetSeconds());
                }
            });
            return found;
        }
    }

    static final class Accounts extends InMemoryRepository<UserAccount, String>
            implements UserAccountRepository {

        @Override
        String idOf(UserAccount entity) {
            return entity.id();
        }

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            for (UserAccount account : rows.values()) {
                if (account.username().equals(username)) {
                    return Optional.of(account);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<UserAccount> findByMasterTrueAndActiveTrue() {
            List<UserAccount> found = new ArrayList<UserAccount>();
            for (UserAccount account : rows.values()) {
                if (account.isMaster() && account.isActive()) {
                    found.add(account);
                }
            }
            return found;
        }

        @Override
        public long countByMasterTrueAndActiveTrue() {
            return findByMasterTrueAndActiveTrue().size();
        }
    }

    static final class Positions extends InMemoryRepository<Position, String>
            implements PositionRepository {

        final Map<String, List<String>> jobFunctionsByPosition =
                new LinkedHashMap<String, List<String>>();

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
            Set<String> found = new LinkedHashSet<String>();
            for (String positionId : positionIds) {
                List<String> functionIds = jobFunctionsByPosition.get(positionId);
                if (functionIds != null) {
                    found.addAll(functionIds);
                }
            }
            return new ArrayList<String>(found);
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

    static final class Ranks extends InMemoryRepository<Rank, String> implements RankRepository {

        @Override
        String idOf(Rank entity) {
            return entity.id();
        }

        @Override
        public List<Rank> findByCompanyIdOrderBySeniorityDesc(String companyId) {
            List<Rank> found = new ArrayList<Rank>();
            for (Rank rank : rows.values()) {
                if (rank.companyId().equals(companyId)) {
                    found.add(rank);
                }
            }
            found.sort(new Comparator<Rank>() {
                @Override
                public int compare(Rank left, Rank right) {
                    return Integer.compare(right.seniority(), left.seniority());
                }
            });
            return found;
        }

        @Override
        public List<Rank> findByCompanyIdAndRepresentativeTrueAndActiveTrue(String companyId) {
            List<Rank> found = new ArrayList<Rank>();
            for (Rank rank : findByCompanyIdOrderBySeniorityDesc(companyId)) {
                if (rank.isRepresentative() && rank.isActive()) {
                    found.add(rank);
                }
            }
            return found;
        }
    }

    static final class Functions extends InMemoryRepository<JobFunction, String>
            implements JobFunctionRepository {

        @Override
        String idOf(JobFunction entity) {
            return entity.id();
        }

        @Override
        public List<JobFunction> findByCompanyIdAndActiveTrue(String companyId) {
            List<JobFunction> found = new ArrayList<JobFunction>();
            for (JobFunction function : rows.values()) {
                if (function.companyId().equals(companyId) && function.isActive()) {
                    found.add(function);
                }
            }
            return found;
        }
    }

    static final class Employees extends InMemoryRepository<Employee, String>
            implements EmployeeRepository {

        @Override
        String idOf(Employee entity) {
            return entity.id();
        }

        @Override
        public List<Employee> findByCompanyId(String companyId) {
            List<Employee> found = new ArrayList<Employee>();
            for (Employee employee : rows.values()) {
                if (employee.companyId().equals(companyId)) {
                    found.add(employee);
                }
            }
            return found;
        }
    }

    /**
     * Grants, held per account. Deny by default; a test grants what it means to.
     *
     * <p>Feeds the real {@link DefaultPermissionEvaluator}, so the scope on a
     * grant matters here exactly as much as it does in an installation.
     */
    static final class Grants implements GrantDirectory {

        private final Map<String, List<PermissionGrant>> byAccount =
                new LinkedHashMap<String, List<PermissionGrant>>();

        void grant(String accountId, PermissionKey key, PermissionScope scope) {
            List<PermissionGrant> held = byAccount.get(accountId);
            if (held == null) {
                held = new ArrayList<PermissionGrant>();
                byAccount.put(accountId, held);
            }
            held.add(PermissionGrant.allow(key, scope, GrantSource.USER_ACCOUNT, accountId,
                    "granted directly in a test"));
        }

        /** Company-wide, for the many tests whose subject is not authorisation. */
        void grantCompanyWide(PermissionKey key, String... accountIds) {
            for (String accountId : accountIds) {
                grant(accountId, key, PermissionScope.COMPANY);
            }
        }

        @Override
        public List<PermissionGrant> grantsFor(PermissionPrincipal principal,
                PrincipalOrgState orgState) {
            List<PermissionGrant> held = byAccount.get(principal.accountId());
            return held == null ? Immutables.<PermissionGrant>listOf() : held;
        }
    }

    /** The org chart as of a date, over the in-memory positions. */
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

    static final class RecordingNotifier implements Notifier {

        final List<ApprovalNotification> sent = new ArrayList<ApprovalNotification>();
        boolean available = true;
        boolean throwOnDelivery;

        @Override
        public String channel() {
            return "in-app";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void notify(ApprovalNotification notification) {
            if (throwOnDelivery) {
                throw new IllegalStateException("the mail server is unreachable");
            }
            sent.add(notification);
        }

        List<ApprovalNotification> to(String accountId) {
            List<ApprovalNotification> found = new ArrayList<ApprovalNotification>();
            for (ApprovalNotification notification : sent) {
                if (notification.recipientAccountId().equals(accountId)) {
                    found.add(notification);
                }
            }
            return found;
        }
    }

    static final class Templates implements ApprovalLineTemplateStore {

        final List<ApprovalLineTemplate> all = new ArrayList<ApprovalLineTemplate>();

        @Override
        public List<ApprovalLineTemplate> findActive(String companyId, String documentType) {
            List<ApprovalLineTemplate> found = new ArrayList<ApprovalLineTemplate>();
            for (ApprovalLineTemplate template : all) {
                if (template.documentType().equals(documentType)) {
                    found.add(template);
                }
            }
            return found;
        }
    }

    static final class FixedRepresentation implements RepresentationDirectory {

        RepresentationMode mode = RepresentationMode.several(1);

        @Override
        public RepresentationMode modeOn(String companyId, LocalDate businessDate) {
            return mode;
        }
    }

    static final class RulesStore implements EmploymentRulesStore {

        final List<EmploymentRules> versions = new ArrayList<EmploymentRules>();
        final Map<String, Set<String>> acknowledgements =
                new LinkedHashMap<String, Set<String>>();

        @Override
        public List<EmploymentRules> findAllVersions(String companyId) {
            return Immutables.copyOf(versions);
        }

        @Override
        public Optional<EmploymentRules> findEffectiveOn(String companyId, LocalDate on) {
            EmploymentRules best = null;
            for (EmploymentRules candidate : versions) {
                if (!candidate.isEffectiveOn(on)) {
                    continue;
                }
                if (best == null || candidate.effectiveFrom().isAfter(best.effectiveFrom())) {
                    best = candidate;
                }
            }
            return Optional.ofNullable(best);
        }

        @Override
        public int nextVersionNumber(String companyId) {
            return versions.size() + 1;
        }

        @Override
        public void save(EmploymentRules rules) {
            versions.add(rules);
        }

        @Override
        public void acknowledge(String rulesId, String employeeId, LocalDate acknowledgedOn) {
            Set<String> people = acknowledgements.get(rulesId);
            if (people == null) {
                people = new LinkedHashSet<String>();
                acknowledgements.put(rulesId, people);
            }
            people.add(employeeId);
        }

        @Override
        public List<String> acknowledgedBy(String rulesId) {
            Set<String> people = acknowledgements.get(rulesId);
            return people == null
                    ? Immutables.<String>listOf()
                    : new ArrayList<String>(people);
        }
    }
}
