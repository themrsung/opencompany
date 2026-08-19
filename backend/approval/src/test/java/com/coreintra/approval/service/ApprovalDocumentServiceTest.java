package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drafting and submitting, and the two things submission freezes.
 *
 * <p>The one worth reading first is
 * {@link Submission#reorganisationDoesNotRerouteAnInFlightApproval()}: it is the
 * reason resolution happens once and is snapshotted, and it is the failure mode
 * that would be invisible until an auditor asked who was supposed to have signed.
 */
class ApprovalDocumentServiceTest {

    private static final String EXPENSE = "EXPENSE_CLAIM";

    private ApprovalTestWorld world;
    private ApprovalDocumentService service;
    private OrgUnit devTeam;
    private OrgUnit division;
    private Rank staff;
    private Rank generalManager;
    private Rank director;
    private String drafter;
    private String bujang;
    private String isa;

    @BeforeEach
    void setUp() {
        world = new ApprovalTestWorld();
        division = world.unit("HQ", null);
        devTeam = world.unit("DEV", division);
        staff = world.rank("SAWON", 10, false);
        generalManager = world.rank("BUJANG", 50, false);
        director = world.rank("ISA", 60, false);

        drafter = world.person("김사원", devTeam, staff, LocalDate.of(2024, 3, 1));
        bujang = world.person("박부장", devTeam, generalManager, LocalDate.of(2019, 1, 1));
        isa = world.person("정이사", division, director, LocalDate.of(2015, 1, 1));

        world.grants.grantCompanyWide(ApprovalPermissions.DOCUMENT_WRITE, drafter);
        world.templates.all.add(expenseTemplate());
        service = world.documentService();
    }

    /** 기안 → 부장 결재, with a 이사 step above five million won. */
    private ApprovalLineTemplate expenseTemplate() {
        List<ApprovalLineTemplate.TemplateStep> base =
                new ArrayList<ApprovalLineTemplate.TemplateStep>();
        base.add(new ApprovalLineTemplate.TemplateStep(0, ApprovalStepKind.DRAFT,
                RoleExpression.account(drafter), false));
        base.add(new ApprovalLineTemplate.TemplateStep(1, ApprovalStepKind.APPROVE,
                RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), false));

        List<ApprovalLineTemplate.TemplateStep> overFiveMillion =
                new ArrayList<ApprovalLineTemplate.TemplateStep>();
        overFiveMillion.add(new ApprovalLineTemplate.TemplateStep(2, ApprovalStepKind.APPROVE,
                RoleExpression.rank("ISA", RoleExpression.Domain.COMPANY), false));

        List<ApprovalLineTemplate.ThresholdRule> rules =
                new ArrayList<ApprovalLineTemplate.ThresholdRule>();
        rules.add(new ApprovalLineTemplate.ThresholdRule(new BigDecimal("5000000"),
                overFiveMillion, "5,000,000원을 초과하는 지출은 이사 결재가 필요합니다."));
        return new ApprovalLineTemplate("tpl-expense", EXPENSE, null, base, rules);
    }

    private ApprovalDocumentEntity draftExpense(String amount) {
        return service.draft(world.principal(drafter), new DraftRequest(ApprovalTestWorld.COMPANY,
                EXPENSE, "출장 교통비", amount == null ? null : new BigDecimal(amount),
                amount == null ? null : "KRW", ApprovalTestWorld.DAY));
    }

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(ApprovalTestWorld.DAY, hour, 0, 0);
    }

    @Nested
    @DisplayName("drafting")
    class Drafting {

        @Test
        @DisplayName("a draft records the drafter's unit as of the document's business date")
        void draftRecordsUnit() {
            ApprovalDocumentEntity document = draftExpense("300000");

            assertThat(document.state()).isEqualTo(ApprovalState.DRAFTING);
            assertThat(document.drafterOrgUnitId()).isEqualTo(devTeam.id());
            assertThat(document.submittedAt())
                    .as("a draft has not been submitted, so it has no submission instant")
                    .isNull();
        }

        @Test
        @DisplayName("drafting without permission is refused, and the denial explains itself")
        void draftingNeedsPermission() {
            PermissionPrincipal outsider = world.principal(bujang);

            assertThatThrownBy(() -> service.draft(outsider, new DraftRequest(
                    ApprovalTestWorld.COMPANY, EXPENSE, "x", null, null, ApprovalTestWorld.DAY)))
                    .isInstanceOf(PermissionDeniedException.class)
                    .hasMessageContaining("approval.document:write");
        }

        @Test
        @DisplayName("an amount with no currency is refused: that is not money")
        void amountNeedsCurrency() {
            assertThatThrownBy(() -> new DraftRequest(ApprovalTestWorld.COMPANY, EXPENSE, "x",
                    new BigDecimal("1000"), null, ApprovalTestWorld.DAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not money");
        }
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        @Test
        @DisplayName("resolves the role expressions to people and snapshots them onto the document")
        void resolvesAndSnapshots() {
            ApprovalDocumentEntity draft = draftExpense("300000");
            ApprovalDocumentView view = service.submit(world.principal(drafter), draft.id(),
                    at(9), "body-v1");

            assertThat(view.state()).isEqualTo(ApprovalState.IN_PROGRESS);
            assertThat(view.steps()).hasSize(2);

            List<ApprovalStepApproverEntity> approving =
                    view.approversOf(view.steps().get(1).id());
            assertThat(approving).hasSize(1);
            assertThat(approving.get(0).accountId()).isEqualTo(bujang);
            assertThat(approving.get(0).resolvedRankLabel())
                    .as("the rank held on the day, snapshotted for the 결재란")
                    .isEqualTo("BUJANG");
            assertThat(approving.get(0).resolvedDisplayName()).isEqualTo("박부장");
        }

        @Test
        @DisplayName("a reorganisation after submission does not reroute an in-flight approval")
        void reorganisationDoesNotRerouteAnInFlightApproval() {
            ApprovalDocumentEntity draft = draftExpense("300000");
            ApprovalDocumentView submitted = service.submit(world.principal(drafter), draft.id(),
                    at(9), "body-v1");
            String approvingStep = submitted.steps().get(1).id();
            assertThat(submitted.approversOf(approvingStep).get(0).accountId()).isEqualTo(bujang);

            // 박부장 moves to another division the next day and 정이사 takes over
            // the team. Every role expression would now resolve differently.
            for (Position position : world.positions.findActiveOn("emp-박부장",
                    ApprovalTestWorld.DAY)) {
                position.closeOn(ApprovalTestWorld.DAY.plusDays(1));
            }
            Position replacement = new Position("pos-신임부장", "emp-정이사", devTeam.id(),
                    generalManager.id(), ApprovalTestWorld.DAY.plusDays(1));
            world.positions.save(replacement);

            ApprovalDocumentView reread = service.read(world.principal(drafter), draft.id());
            assertThat(reread.approversOf(approvingStep))
                    .extracting(ApprovalStepApproverEntity::accountId)
                    .as("the line was resolved at submission and is not re-derived; a reorg "
                            + "must not change who was routed a document already in flight")
                    .containsExactly(bujang);
            assertThat(reread.line().isAwaiting(bujang)).isTrue();
            assertThat(reread.line().isAwaiting(isa)).isFalse();
        }

        @Test
        @DisplayName("an expense over the threshold gains the 이사 step; one under it does not")
        void thresholdAddsAStep() {
            ApprovalDocumentEntity small = draftExpense("4999999");
            assertThat(service.submit(world.principal(drafter), small.id(), at(9), null).steps())
                    .hasSize(2);

            ApprovalDocumentEntity large = draftExpense("5000000");
            ApprovalDocumentView view = service.submit(world.principal(drafter), large.id(),
                    at(10), null);

            assertThat(view.steps())
                    .as("the rule is inclusive: 5,000,000 is over the threshold, exactly")
                    .hasSize(3);
            assertThat(view.approversOf(view.steps().get(2).id()))
                    .extracting(ApprovalStepApproverEntity::accountId)
                    .containsExactly(isa);
        }

        @Test
        @DisplayName("the submitted artefact is immutable — editing it afterwards is refused")
        void submittedDocumentsCannotBeEdited() {
            ApprovalDocumentEntity draft = draftExpense("300000");
            service.submit(world.principal(drafter), draft.id(), at(9), "body-v1");

            assertThatThrownBy(() -> service.updateDraft(world.principal(drafter), draft.id(),
                    "고친 제목", new BigDecimal("9000000"), "KRW", ApprovalTestWorld.DAY))
                    .isInstanceOf(ApprovalDocumentService.DocumentStateException.class)
                    .hasMessageContaining("immutable");
        }

        @Test
        @DisplayName("the payload digest is frozen at submission and covers the amount")
        void snapshotHashIsFrozen() {
            ApprovalDocumentEntity draft = draftExpense("300000");
            ApprovalDocumentView view = service.submit(world.principal(drafter), draft.id(),
                    at(9), "body-v1");
            String hash = view.document().submittedSnapshotHash();

            assertThat(hash).startsWith("sha256:");

            ApprovalDocumentEntity other = draftExpense("300001");
            String otherHash = service.submit(world.principal(drafter), other.id(), at(9),
                    "body-v1").document().submittedSnapshotHash();
            assertThat(otherHash)
                    .as("a different amount is a different document, provably")
                    .isNotEqualTo(hash);
        }

        @Test
        @DisplayName("a required step that resolves to nobody refuses the submission")
        void unresolvableRequiredStepRefused() {
            // Nobody in 총무팀 holds 부장, so the line cannot be completed.
            OrgUnit generalAffairs = world.unit("GA", division);
            String loner = world.person("이사원", generalAffairs, staff, LocalDate.of(2025, 1, 1));
            world.grants.grantCompanyWide(ApprovalPermissions.DOCUMENT_WRITE, loner);

            ApprovalDocumentEntity draft = service.draft(world.principal(loner), new DraftRequest(
                    ApprovalTestWorld.COMPANY, EXPENSE, "비품 구입", new BigDecimal("10000"), "KRW",
                    ApprovalTestWorld.DAY));

            assertThatThrownBy(() -> service.submit(world.principal(loner), draft.id(), at(9),
                    null))
                    .isInstanceOf(ApproverDirectory.UnresolvableRoleException.class)
                    .hasMessageContaining("silently skipped a signature");
        }

        @Test
        @DisplayName("an optional step that resolves to nobody is dropped instead")
        void optionalStepDropped() {
            List<ApprovalLineTemplate.TemplateStep> base =
                    new ArrayList<ApprovalLineTemplate.TemplateStep>();
            base.add(new ApprovalLineTemplate.TemplateStep(0, ApprovalStepKind.DRAFT,
                    RoleExpression.account(drafter), false));
            base.add(new ApprovalLineTemplate.TemplateStep(1, ApprovalStepKind.CONCURRENCE,
                    RoleExpression.jobFunction("LEGAL", RoleExpression.Domain.COMPANY), true));
            base.add(new ApprovalLineTemplate.TemplateStep(2, ApprovalStepKind.APPROVE,
                    RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT), false));
            world.functions.save(new com.coreintra.core.org.JobFunction("fn-legal",
                    ApprovalTestWorld.COMPANY, "LEGAL", "법무"));
            world.templates.all.clear();
            world.templates.all.add(new ApprovalLineTemplate("tpl-legal", EXPENSE, null, base,
                    Immutables.<ApprovalLineTemplate.ThresholdRule>listOf()));

            ApprovalDocumentEntity draft = draftExpense("1000");
            ApprovalDocumentView view = service.submit(world.principal(drafter), draft.id(),
                    at(9), null);

            assertThat(view.steps())
                    .as("a 법무 concurrence in a company with no legal team is dropped, not stalled")
                    .hasSize(2);
        }

        @Test
        @DisplayName("submitting twice is refused")
        void doubleSubmissionRefused() {
            ApprovalDocumentEntity draft = draftExpense("1000");
            service.submit(world.principal(drafter), draft.id(), at(9), null);

            assertThatThrownBy(() -> service.submit(world.principal(drafter), draft.id(), at(10),
                    null))
                    .isInstanceOf(ApprovalDocumentService.DocumentStateException.class)
                    .hasMessageContaining("already been submitted");
        }

        @Test
        @DisplayName("the approver is told there is something waiting")
        void approverIsNotified() {
            ApprovalDocumentEntity draft = draftExpense("1000");
            service.submit(world.principal(drafter), draft.id(), at(9), null);

            assertThat(world.notifier.to(bujang))
                    .extracting(ApprovalNotification::kind)
                    .containsExactly(ApprovalNotification.Kind.AWAITING_YOUR_ACTION);
            assertThat(world.notifier.to(bujang).get(0).subjectKo()).startsWith("[결재 요청]");
        }

        @Test
        @DisplayName("a company with no template for the type refuses, naming the fix")
        void noTemplateRefused() {
            world.templates.all.clear();
            ApprovalDocumentEntity draft = draftExpense("1000");

            assertThatThrownBy(() -> service.submit(world.principal(drafter), draft.id(), at(9),
                    null))
                    .isInstanceOf(ApprovalLineTemplateService.NoTemplateException.class)
                    .hasMessageContaining("company default");
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("an approver can open what they were routed without any grant")
        void routedApproverCanRead() {
            ApprovalDocumentEntity draft = draftExpense("1000");
            service.submit(world.principal(drafter), draft.id(), at(9), null);

            // 박부장 holds no approval.document:read grant at all.
            ApprovalDocumentView view = service.read(world.principal(bujang), draft.id());
            assertThat(view.isAwaiting(bujang)).isTrue();
        }

        @Test
        @DisplayName("someone with no part in the document needs a grant to read it")
        void bystanderNeedsGrant() {
            ApprovalDocumentEntity draft = draftExpense("1000");
            service.submit(world.principal(drafter), draft.id(), at(9), null);
            String bystander = world.person("최사원", division, staff, LocalDate.of(2024, 1, 1));

            assertThatThrownBy(() -> service.read(world.principal(bystander), draft.id()))
                    .isInstanceOf(PermissionDeniedException.class);

            world.grants.grant(bystander, ApprovalPermissions.DOCUMENT_READ,
                    PermissionScope.COMPANY);
            assertThat(service.read(world.principal(bystander), draft.id()).document().id())
                    .isEqualTo(draft.id());
        }
    }
}
