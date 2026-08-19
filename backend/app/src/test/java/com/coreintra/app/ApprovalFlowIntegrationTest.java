package com.coreintra.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.entity.ApprovalTemplateStepEntity;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.ApprovalDocumentRepository;
import com.coreintra.approval.repository.ApprovalLineTemplateRepository;
import com.coreintra.approval.repository.ApprovalStepRepository;
import com.coreintra.approval.repository.ApprovalTemplateStepRepository;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.approval.service.ApprovalActionService;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalDocumentView;
import com.coreintra.approval.service.ApprovalInbox;
import com.coreintra.approval.service.ApprovalInboxService;
import com.coreintra.approval.service.DraftRequest;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.app.config.ApprovalWiring;
import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.attendance.entity.LeavePolicy;
import com.coreintra.attendance.entity.LeaveTransaction;
import com.coreintra.attendance.repository.LeavePolicyRepository;
import com.coreintra.attendance.repository.LeaveTransactionRepository;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.EmployeeRepository;
import com.coreintra.core.org.repository.OrgUnitRepository;
import com.coreintra.core.org.repository.PositionRepository;
import com.coreintra.core.org.repository.RankRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.documents.internal.BinaryStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 결재 end to end, against a real PostgreSQL.
 *
 * <p>What this covers that the unit suites cannot. The service layer is tested
 * against in-memory repositories, which proves its rules and proves nothing at
 * all about whether the rules can be persisted: until this test existed, the
 * ports had no adapters, the Spring context did not start, the inbox JPQL had
 * never been parsed by a database, and {@code employment_rules} did not exist as
 * a table.
 *
 * <p>Every fixture uses fresh ids rather than truncating shared tables. Three
 * other agents are writing tests against the same schema, and a
 * {@code delete from company} in a {@code @BeforeEach} is how one suite silently
 * breaks another.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalFlowIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    /**
     * Scaffolding, and meant to be deleted.
     *
     * <p>{@code SeededTemplateInstaller} in the documents module constructor-
     * injects a {@link BinaryStore}, and nothing in the installation provides
     * one yet, so the application context does not start at all — this test and
     * every other {@code @SpringBootTest} in the module fail identically before
     * reaching a single assertion. Approvals do not touch it: no path exercised
     * below calls the installer, so a stub that refuses is honest rather than
     * convenient. Remove this the moment the documents module wires the real
     * blob-backed store.
     */
    @MockBean
    private BinaryStore binaryStoreNotYetWiredByTheDocumentsModule;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 18);
    /** 09:00 on the business day. Ordinary office hours, ordinary offset. */
    private static final BusinessInstant NOW = BusinessInstant.of(TODAY, 9 * 3600);

    @Autowired private CompanyRepository companies;
    @Autowired private OrgUnitRepository orgUnits;
    @Autowired private RankRepository ranks;
    @Autowired private EmployeeRepository employees;
    @Autowired private UserAccountRepository accounts;
    @Autowired private PositionRepository positions;
    @Autowired private PermissionGrantRepository grants;

    @Autowired private CompanyRepresentationRepository representations;
    @Autowired private ApprovalLineTemplateRepository templates;
    @Autowired private ApprovalTemplateStepRepository templateSteps;
    @Autowired private ApprovalDocumentRepository approvalDocuments;
    @Autowired private ApprovalStepRepository approvalSteps;

    @Autowired private LeavePolicyRepository leavePolicies;
    @Autowired private LeaveTransactionRepository leaveTransactions;

    @Autowired private ApprovalDocumentService documentService;
    @Autowired private ApprovalActionService actionService;
    @Autowired private ApprovalInboxService inboxService;
    @Autowired private EmploymentRulesService employmentRules;

    @Autowired private DataSource dataSource;

    private String companyId;
    private String unitId;
    private String policyId;

    private String drafterAccountId;
    private String drafterEmployeeId;
    private String leaderAccountId;
    private String firstRepAccountId;
    private String secondRepAccountId;

    @BeforeEach
    void seedOneCompany() {
        companyId = id();
        companies.save(new Company(companyId, "co-" + shortId(), "에이스전자",
                Company.CompanyKind.HEAD_OFFICE));

        OrgUnit unit = new OrgUnit(id(), companyId, "dev", "개발팀");
        orgUnits.save(unit);
        unitId = unit.id();

        Rank staff = new Rank(id(), companyId, "STAFF", "사원", 10);
        Rank leader = new Rank(id(), companyId, "TEAM_LEAD", "팀장", 50);
        Rank chief = new Rank(id(), companyId, "CEO", "대표이사", 90);
        // The 대표 is read from this flag rather than from the label, so a client
        // who calls the post something else still gets a working quorum.
        chief.setRepresentative(true);
        ranks.save(staff);
        ranks.save(leader);
        ranks.save(chief);

        drafterEmployeeId = employee("김민준");
        drafterAccountId = account("김민준", drafterEmployeeId);
        positions.save(new Position(id(), drafterEmployeeId, unitId, staff.id(),
                LocalDate.of(2020, 1, 1)));

        String leaderEmployeeId = employee("이서연");
        leaderAccountId = account("이서연", leaderEmployeeId);
        positions.save(new Position(id(), leaderEmployeeId, unitId, leader.id(),
                LocalDate.of(2020, 1, 1)));

        String firstRepEmployeeId = employee("박지훈");
        firstRepAccountId = account("박지훈", firstRepEmployeeId);
        positions.save(new Position(id(), firstRepEmployeeId, unitId, chief.id(),
                LocalDate.of(2020, 1, 1)));

        String secondRepEmployeeId = employee("최수아");
        secondRepAccountId = account("최수아", secondRepEmployeeId);
        positions.save(new Position(id(), secondRepEmployeeId, unitId, chief.id(),
                LocalDate.of(2020, 1, 1)));

        allow(drafterAccountId, "approval.document:write");
        allow(drafterAccountId, "hr.rules:write");
        allow(drafterAccountId, "hr.rules:read");

        // 공동대표, all of two. Chosen for the whole fixture so that the quorum
        // is exercised by anything that reaches a representative step, and so
        // that a leave line — which reaches no such step — is unaffected by it.
        representations.save(new CompanyRepresentationEntity(id(), companyId,
                RepresentationMode.jointAll(2), LocalDate.of(2020, 1, 1), null));

        seedLeavePolicy();
        seedLeaveRequestTemplate();
        seedEmploymentRulesTemplate();
    }

    // ------------------------------------------------------------------
    // 1. Approving a 휴가신청서 writes the balance transaction

    @Test
    @DisplayName("approving a 휴가신청서 writes the leave ledger exactly once, as part of the approval")
    void approvalWritesTheLeaveTransactionExactlyOnce() {
        grantDays("15");
        String documentId = submitLeaveRequest(new BigDecimal("1.5"), "여름 휴가");

        assertThat(leaveTransactions.findBySourceDocumentId(documentId))
                .as("submitting deducts nothing; only an approval spends leave")
                .isEmpty();

        ApprovalDocumentView approved = actionService.approve(leaderPrincipal(), documentId,
                pendingStepId(documentId), NOW, "확인했습니다");

        assertThat(approved.state()).isEqualTo(ApprovalState.APPROVED);

        List<LeaveTransaction> written = leaveTransactions.findBySourceDocumentId(documentId);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).kind()).isEqualTo(LeaveLedger.TransactionKind.USE);
        assertThat(written.get(0).days()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(written.get(0).employeeId()).isEqualTo(drafterEmployeeId);
        assertThat(written.get(0).actorAccountId())
                .as("the trail names whoever approved, not whoever asked")
                .isEqualTo(leaderAccountId);
        assertThat(written.get(0).occurredBusinessDate()).isEqualTo(TODAY);

        // Balance: 15 granted, 1.5 spent. Computed from the rows, never stored.
        assertThat(ledgerBalance()).isEqualByComparingTo(new BigDecimal("13.5"));
    }

    @Test
    @DisplayName("a second approval of a settled document neither succeeds nor deducts again")
    void aSettledDocumentCannotDeductTwice() {
        grantDays("15");
        String documentId = submitLeaveRequest(BigDecimal.ONE, "연차");
        String stepId = pendingStepId(documentId);

        actionService.approve(leaderPrincipal(), documentId, stepId, NOW, null);
        assertThatThrownBy(() ->
                actionService.approve(leaderPrincipal(), documentId, stepId, NOW, null))
                .isInstanceOf(ApprovalLine.ApprovalRuleException.class);

        assertThat(leaveTransactions.findBySourceDocumentId(documentId)).hasSize(1);
    }

    @Test
    @DisplayName("a leave deduction the balance cannot cover takes the whole approval down with it")
    void arefusedDeductionRollsTheApprovalBack() {
        // The discrepancy this prevents: an approved 휴가 and an unchanged
        // balance, which nobody finds until it is disputed months later.
        grantDays("1");
        String documentId = submitLeaveRequest(new BigDecimal("5"), "장기 휴가");
        String stepId = pendingStepId(documentId);

        assertThatThrownBy(() ->
                actionService.approve(leaderPrincipal(), documentId, stepId, NOW, null))
                .hasMessageContaining("잔여");

        ApprovalDocumentEntity afterwards = approvalDocuments.findById(documentId).get();
        assertThat(afterwards.state())
                .as("nothing was written: not the ledger row, and not the approval either")
                .isEqualTo(ApprovalState.IN_PROGRESS);
        assertThat(leaveTransactions.findBySourceDocumentId(documentId)).isEmpty();
    }

    @Test
    @DisplayName("a 휴가신청서 with no readable body is refused rather than guessed at")
    void aLeaveRequestWithNoBodyIsRefused() {
        grantDays("15");
        ApprovalDocumentEntity draft = documentService.draft(drafterPrincipal(),
                new DraftRequest(companyId, ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE,
                        "휴가신청서", null, null, TODAY));
        documentService.submit(drafterPrincipal(), draft.id(), NOW, null);

        assertThatThrownBy(() -> actionService.approve(leaderPrincipal(), draft.id(),
                pendingStepId(draft.id()), NOW, null))
                .hasMessageContaining("본문");

        assertThat(leaveTransactions.findBySourceDocumentId(draft.id())).isEmpty();
    }

    // ------------------------------------------------------------------
    // 2. The inbox query

    @Test
    @DisplayName("the inbox query executes against PostgreSQL and finds the waiting document")
    void theInboxQueryRuns() {
        // Its JPQL had never been parsed by a database. Both forms are exercised
        // here — the service's own query and the repository's @Query — because
        // both are on the path of one of the two screens the brief says decide
        // whether people like this product.
        grantDays("15");
        String documentId = submitLeaveRequest(BigDecimal.ONE, "연차");

        ApprovalInbox inbox = inboxService.load(leaderPrincipal(), companyId, leaderAccountId);

        assertThat(inbox.awaitingCount()).isEqualTo(1);
        assertThat(inbox.awaitingMe().get(0).id()).isEqualTo(documentId);

        ApprovalInbox drafterInbox =
                inboxService.load(drafterPrincipal(), companyId, drafterAccountId);
        assertThat(drafterInbox.awaitingMe())
                .as("the drafter is waiting on their 팀장, not on themselves")
                .isEmpty();
        assertThat(drafterInbox.draftedByMe()).extracting("id").contains(documentId);

        List<ApprovalDocumentEntity> viaRepository = approvalDocuments.findInbox(leaderAccountId,
                Immutables.listOf(ApprovalState.IN_PROGRESS, ApprovalState.PARTIALLY_APPROVED),
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(viaRepository).extracting("id").containsExactly(documentId);
    }

    @Test
    @DisplayName("an approved document leaves the approver's inbox")
    void theInboxEmptiesOnceSettled() {
        grantDays("15");
        String documentId = submitLeaveRequest(BigDecimal.ONE, "연차");
        actionService.approve(leaderPrincipal(), documentId, pendingStepId(documentId), NOW, null);

        assertThat(inboxService.load(leaderPrincipal(), companyId, leaderAccountId).awaitingMe())
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 3. 공동대표 quorum

    @Test
    @DisplayName("공동대표 quorum is not satisfiable by one representative, however many times they sign")
    void jointRepresentationNeedsTwoDistinctRepresentatives() {
        String documentId = submitEmploymentRulesProposal();
        String stepId = pendingStepId(documentId);

        ApprovalDocumentView afterFirst = actionService.approve(firstRepPrincipal(), documentId,
                stepId, NOW, null);

        assertThat(afterFirst.state())
                .as("partial approval is a visible state of its own, not 'still waiting'")
                .isEqualTo(ApprovalState.PARTIALLY_APPROVED);

        assertThatThrownBy(() ->
                actionService.approve(firstRepPrincipal(), documentId, stepId, NOW, null))
                .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                .hasMessageContaining("distinct");

        assertThat(approvalDocuments.findById(documentId).get().state())
                .isEqualTo(ApprovalState.PARTIALLY_APPROVED);

        ApprovalDocumentView afterSecond = actionService.approve(secondRepPrincipal(), documentId,
                stepId, NOW, null);
        assertThat(afterSecond.state()).isEqualTo(ApprovalState.APPROVED);
    }

    // ------------------------------------------------------------------
    // 4. 취업규칙 needs 대표자 결재, by every path

    @Test
    @DisplayName("취업규칙 cannot be enacted with no approval document at all")
    void employmentRulesNeedAnApprovalDocument() {
        assertThatThrownBy(() -> employmentRules.publish(drafterPrincipal(), companyId, TODAY,
                sections(), null))
                .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                .hasMessageContaining("대표자 결재");
    }

    @Test
    @DisplayName("취업규칙 cannot be enacted while its approval is only partly signed")
    void employmentRulesNeedTheQuorumComplete() {
        String documentId = submitEmploymentRulesProposal();
        actionService.approve(firstRepPrincipal(), documentId, pendingStepId(documentId), NOW, null);

        assertThatThrownBy(() -> employmentRules.publish(drafterPrincipal(), companyId, TODAY,
                sections(), documentId))
                .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                .hasMessageContaining("PARTIALLY_APPROVED");

        assertThat(employmentRules.effectiveOn(drafterPrincipal(), companyId, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("with the quorum met, 취업규칙 is enacted and readable back as of a date")
    void employmentRulesAreEnactedOnceBothRepresentativesSign() {
        String documentId = submitEmploymentRulesProposal();
        String stepId = pendingStepId(documentId);
        actionService.approve(firstRepPrincipal(), documentId, stepId, NOW, null);
        actionService.approve(secondRepPrincipal(), documentId, stepId, NOW, null);

        EmploymentRules published = employmentRules.publish(drafterPrincipal(), companyId,
                LocalDate.of(2026, 9, 1), sections(), documentId);

        assertThat(published.version()).isEqualTo(1);
        assertThat(published.approvingRepresentativeIds())
                .containsExactlyInAnyOrder(firstRepAccountId, secondRepAccountId);

        assertThat(employmentRules.effectiveOn(drafterPrincipal(), companyId,
                LocalDate.of(2026, 8, 31)))
                .as("a version taking effect in September does not bind anyone in August")
                .isEmpty();

        EmploymentRules inForce = employmentRules
                .effectiveOn(drafterPrincipal(), companyId, LocalDate.of(2026, 9, 1)).get();
        assertThat(inForce.sections()).hasSize(2);
        assertThat(inForce.sections().get(1).headingKo()).isEqualTo("근로시간");
        assertThat(inForce.approvedUnderMode().isJoint()).isTrue();
    }

    @Test
    @DisplayName("no SQL path enacts 취업규칙 either: the row cannot reference an unapproved document")
    void theDatabaseRefusesAnUnapprovedEmploymentRulesRow() {
        // The Java gate is EmploymentRules.publish. This asserts the other half:
        // that someone bypassing the service entirely — a support session at a
        // psql prompt, a restore of a doctored dump, a future API path written
        // by someone who never read the javadoc — still cannot enact rules that
        // no 대표 approved.
        String documentId = submitEmploymentRulesProposal();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThatThrownBy(() -> jdbc.update(
                "insert into employment_rules (id, company_id, version, effective_from, "
                        + "approval_document_id, approval_document_state, approved_under_mode, "
                        + "approved_under_required_approvals, approved_under_designated) "
                        + "values (?, ?, 1, ?, ?, 'APPROVED', 'JOINT', 2, 2)",
                id(), companyId, java.sql.Date.valueOf(TODAY), documentId))
                .as("the document is IN_PROGRESS, so (id, 'APPROVED') matches no row")
                .isInstanceOf(DataIntegrityViolationException.class);

        // And the state cannot simply be misdeclared to get around it.
        assertThatThrownBy(() -> jdbc.update(
                "insert into employment_rules (id, company_id, version, effective_from, "
                        + "approval_document_id, approval_document_state, approved_under_mode, "
                        + "approved_under_required_approvals, approved_under_designated) "
                        + "values (?, ?, 1, ?, ?, 'IN_PROGRESS', 'JOINT', 2, 2)",
                id(), companyId, java.sql.Date.valueOf(TODAY), documentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // Fixture

    private void seedLeavePolicy() {
        LeavePolicy policy = new LeavePolicy(id(), companyId, "ANNUAL", "연차유급휴가 (기본)");
        policy.setMonthlyAccrualDays(BigDecimal.ONE);
        policy.setAnnualGrantDays(new BigDecimal("15"));
        policy.setAnnualGrantAfterYears(1);
        policy.setMaximumDays(new BigDecimal("25"));
        policy.setCarryOverExpiryMonths(12);
        policy.setMinimumBookableUnitDays(new BigDecimal("0.5"));
        policy.setBuiltIn(true);
        leavePolicies.save(policy);
        policyId = policy.id();
    }

    private void seedLeaveRequestTemplate() {
        String templateId = id();
        templates.save(new ApprovalLineTemplateEntity(templateId, companyId,
                ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE, null, "휴가신청서 기본 결재선"));
        templateSteps.save(new ApprovalTemplateStepEntity(id(), templateId, 1,
                ApprovalStepKind.APPROVE, "rank:TEAM_LEAD@DRAFTER_UNIT", false, null, null));
    }

    private void seedEmploymentRulesTemplate() {
        String templateId = id();
        templates.save(new ApprovalLineTemplateEntity(templateId, companyId,
                EmploymentRulesService.DOCUMENT_TYPE, null, "취업규칙 개정 결재선"));
        templateSteps.save(new ApprovalTemplateStepEntity(id(), templateId, 1,
                ApprovalStepKind.APPROVE, "representative@COMPANY", false, null, null));
    }

    /** A 휴가신청서 drafted, given a body, and submitted into its line. */
    private String submitLeaveRequest(BigDecimal days, String reason) {
        ApprovalDocumentEntity draft = documentService.draft(drafterPrincipal(),
                new DraftRequest(companyId, ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE,
                        "휴가신청서", null, null, TODAY));
        draft.setDocumentId(writeLeaveRequestBody(days, reason));
        approvalDocuments.save(draft);

        documentService.submit(drafterPrincipal(), draft.id(), NOW, "sha256:body");
        return draft.id();
    }

    /**
     * The document body, written the way the documents module writes one.
     *
     * <p>Raw SQL rather than that module's entities: this test owns none of
     * them, and the point of the fixture is that the adapter reads the columns
     * that are actually there.
     */
    private String writeLeaveRequestBody(BigDecimal days, String reason) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sha = sha256Hex();
        jdbc.update("insert into blob (sha256, size_bytes, content_type, original_filename) "
                + "values (?, 1, 'application/vnd.openxmlformats-officedocument"
                + ".wordprocessingml.document', '휴가신청서.docx')", sha);

        String bodyId = id();
        jdbc.update("insert into document (id, company_id, document_type, title, "
                + "current_version_no, created_by_account_id) values (?, ?, ?, ?, 1, ?)",
                bodyId, companyId, ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE, "휴가신청서",
                drafterAccountId);
        jdbc.update("insert into document_version (document_id, version_no, blob_sha256, format, "
                + "authored_business_date, authored_offset_seconds, author_account_id) "
                + "values (?, 1, ?, 'DOCX', ?, ?, ?)",
                bodyId, sha, java.sql.Date.valueOf(TODAY), Integer.valueOf(9 * 3600),
                drafterAccountId);

        field(jdbc, bodyId, ApprovalWiring.LeaveRequestFields.EMPLOYEE, "EMPLOYEE_REF",
                "value_ref_id", drafterEmployeeId);
        field(jdbc, bodyId, ApprovalWiring.LeaveRequestFields.POLICY_CODE, "TEXT",
                "value_text", "ANNUAL");
        field(jdbc, bodyId, ApprovalWiring.LeaveRequestFields.DAYS, "NUMBER",
                "value_number", days);
        field(jdbc, bodyId, ApprovalWiring.LeaveRequestFields.REASON, "TEXT",
                "value_text", reason);
        return bodyId;
    }

    private void field(JdbcTemplate jdbc, String documentId, String fieldId, String type,
            String column, Object value) {
        jdbc.update("insert into document_field_value (document_id, version_no, field_id, "
                + "field_type, " + column + ") values (?, 1, ?, ?, ?)",
                documentId, fieldId, type, value);
    }

    /** A 취업규칙 change proposed and filed for 대표자 결재. */
    private String submitEmploymentRulesProposal() {
        ApprovalDocumentEntity proposal = employmentRules.proposeChange(drafterPrincipal(),
                companyId, "취업규칙 개정 (2026)", TODAY);
        documentService.submit(drafterPrincipal(), proposal.id(), NOW,
                EmploymentRulesService.textDigest(sections()));
        return proposal.id();
    }

    private static List<EmploymentRules.Section> sections() {
        return Immutables.listOf(
                new EmploymentRules.Section("제1조", "목적", "Purpose",
                        "이 규칙은 회사의 근로조건을 정함을 목적으로 한다.",
                        "These rules set out the company's terms of employment."),
                new EmploymentRules.Section("제2조", "근로시간", "Working hours",
                        "1주 소정근로시간은 40시간으로 한다.",
                        "Contractual working time is 40 hours per week."));
    }

    private void grantDays(String days) {
        leaveTransactions.save(new LeaveTransaction(drafterEmployeeId, policyId,
                new LeaveLedger.Transaction(id(), LeaveLedger.TransactionKind.GRANT,
                        new BigDecimal(days), BusinessInstant.of(LocalDate.of(2026, 1, 1), 0),
                        null, null, null, null, null)));
    }

    private BigDecimal ledgerBalance() {
        List<LeaveTransaction> rows = leaveTransactions
                .findByEmployeeIdAndPolicyIdOrderByOccurredBusinessDateAscOccurredOffsetSecondsAsc(
                        drafterEmployeeId, policyId);
        List<LeaveLedger.Transaction> domain = new java.util.ArrayList<LeaveLedger.Transaction>();
        for (LeaveTransaction row : rows) {
            domain.add(row.toTransaction());
        }
        return new LeaveLedger(drafterEmployeeId, domain).balanceOn(TODAY);
    }

    /** The one step waiting for someone right now. */
    private String pendingStepId(String documentId) {
        for (ApprovalStepEntity step : approvalSteps.findByDocumentIdOrderByPositionAsc(documentId)) {
            if (step.kind().requiresAction()
                    && step.state() != com.coreintra.approval.domain.ApprovalStep.StepState.COMPLETED) {
                return step.id();
            }
        }
        throw new IllegalStateException("no actionable step on " + documentId);
    }

    private String employee(String name) {
        Employee employee = new Employee(id(), companyId, name);
        employees.save(employee);
        return employee.id();
    }

    private String account(String name, String employeeId) {
        UserAccount account = new UserAccount(id(), name + "-" + shortId(), name,
                UserAccount.AccountKind.USER);
        account.linkToEmployee(employeeId);
        accounts.save(account);
        return account.id();
    }

    private void allow(String accountId, String key) {
        grants.save(new PermissionGrantRow(id(), GrantSource.USER_ACCOUNT, accountId,
                PermissionKey.parse(key), PermissionScope.COMPANY, true));
    }

    private PermissionPrincipal drafterPrincipal() {
        return PermissionPrincipal.user(drafterAccountId, "김민준", drafterEmployeeId);
    }

    private PermissionPrincipal leaderPrincipal() {
        return principalFor(leaderAccountId);
    }

    private PermissionPrincipal firstRepPrincipal() {
        return principalFor(firstRepAccountId);
    }

    private PermissionPrincipal secondRepPrincipal() {
        return principalFor(secondRepAccountId);
    }

    private PermissionPrincipal principalFor(String accountId) {
        UserAccount account = accounts.findById(accountId).get();
        return PermissionPrincipal.user(account.id(), account.displayName(), account.employeeId());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String sha256Hex() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
