package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalAction;
import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.domain.RoleExpression;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Rank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The 결재 actions, and four of the brief's named acceptance tests.
 *
 * <p>Never to regress, from §13:
 *
 * <ul>
 *   <li>공동대표 quorum is not satisfiable by a single approver;</li>
 *   <li>a recall after the first approval is refused;</li>
 *   <li>대결 records whom the actor acted for, and the trail never implies the
 *       absent approver signed personally;</li>
 *   <li>a consequence that must be part of the approval — the leave balance
 *       write — either happens with it or leaves nothing behind.</li>
 * </ul>
 */
class ApprovalActionServiceTest {

    private static final String EXPENSE = "EXPENSE_CLAIM";

    private ApprovalTestWorld world;
    private ApprovalDocumentService documents;
    private ApprovalActionService service;
    private OrgUnit team;
    private Rank staff;
    private Rank generalManager;
    private Rank representative;
    private String drafter;
    private String bujang;

    @BeforeEach
    void setUp() {
        world = new ApprovalTestWorld();
        team = world.unit("DEV", null);
        staff = world.rank("SAWON", 10, false);
        generalManager = world.rank("BUJANG", 50, false);
        representative = world.rank("DAEPYO", 70, true);

        drafter = world.person("김사원", team, staff, LocalDate.of(2024, 1, 1));
        bujang = world.person("박부장", team, generalManager, LocalDate.of(2018, 1, 1));
        world.grants.grantCompanyWide(ApprovalPermissions.DOCUMENT_WRITE, drafter);

        documents = world.documentService();
        service = world.actionService();
    }

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(ApprovalTestWorld.DAY, hour, 0, 0);
    }

    private void useTemplate(ApprovalLineTemplate.TemplateStep... steps) {
        world.templates.all.clear();
        world.templates.all.add(new ApprovalLineTemplate("tpl", EXPENSE, null,
                Immutables.listOfArray(steps),
                Immutables.<ApprovalLineTemplate.ThresholdRule>listOf()));
    }

    private ApprovalLineTemplate.TemplateStep step(int position, ApprovalStepKind kind,
            RoleExpression role) {
        return new ApprovalLineTemplate.TemplateStep(position, kind, role, false);
    }

    private ApprovalDocumentView submit() {
        ApprovalDocumentEntity draft = documents.draft(world.principal(drafter),
                new DraftRequest(ApprovalTestWorld.COMPANY, EXPENSE, "지출결의서",
                        new BigDecimal("100000"), "KRW", ApprovalTestWorld.DAY));
        return documents.submit(world.principal(drafter), draft.id(), at(9), "body-v1");
    }

    private String stepIdOf(ApprovalDocumentView view, ApprovalStepKind kind) {
        for (ApprovalStepEntity step : view.steps()) {
            if (step.kind() == kind) {
                return step.id();
            }
        }
        throw new IllegalStateException("no " + kind + " step");
    }

    @Nested
    @DisplayName("공동대표 quorum")
    class JointRepresentation {

        private String repOne;
        private String repTwo;
        private String repThree;

        @BeforeEach
        void threeRepresentatives() {
            repOne = world.person("대표일", team, representative, LocalDate.of(2010, 1, 1));
            repTwo = world.person("대표이", team, representative, LocalDate.of(2010, 1, 1));
            repThree = world.person("대표삼", team, representative, LocalDate.of(2010, 1, 1));
            world.representation.mode = RepresentationMode.joint(2, 3);
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE, RoleExpression.representative()));
        }

        @Test
        @DisplayName("a single approver never satisfies the quorum, however many times they act")
        void oneApproverCannotSatisfyAQuorum() {
            ApprovalDocumentView view = submit();
            String repStep = stepIdOf(view, ApprovalStepKind.APPROVE);

            ApprovalDocumentView afterOne = service.approve(world.principal(repOne), view
                    .document().id(), repStep, at(10), null);

            assertThat(afterOne.state())
                    .as("partial approval is a distinct, visible state — not 'still waiting'")
                    .isEqualTo(ApprovalState.PARTIALLY_APPROVED);

            assertThatThrownBy(() -> service.approve(world.principal(repOne),
                    view.document().id(), repStep, at(11), null))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("distinct representatives");

            assertThat(documents.read(world.principal(drafter), view.document().id()).state())
                    .as("the refused second attempt changed nothing")
                    .isEqualTo(ApprovalState.PARTIALLY_APPROVED);
        }

        @Test
        @DisplayName("a second, distinct representative completes it")
        void twoDistinctRepresentativesApprove() {
            ApprovalDocumentView view = submit();
            String repStep = stepIdOf(view, ApprovalStepKind.APPROVE);

            service.approve(world.principal(repOne), view.document().id(), repStep, at(10), null);
            ApprovalDocumentView afterTwo = service.approve(world.principal(repTwo),
                    view.document().id(), repStep, at(11), null);

            assertThat(afterTwo.state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(afterTwo.trail()).hasSize(2);
            assertThat(afterTwo.line().isAwaiting(repThree))
                    .as("the quorum was two; the third 대표 is no longer expected to act")
                    .isFalse();
        }

        @Test
        @DisplayName("the quorum is snapshotted, so changing the mode mid-flight does not lower it")
        void quorumIsSnapshotted() {
            ApprovalDocumentView view = submit();
            String repStep = stepIdOf(view, ApprovalStepKind.APPROVE);
            service.approve(world.principal(repOne), view.document().id(), repStep, at(10), null);

            // The company switches to 각자대표 the next morning.
            world.representation.mode = RepresentationMode.several(3);

            ApprovalDocumentView reread = documents.read(world.principal(drafter),
                    view.document().id());
            assertThat(reread.state())
                    .as("a document submitted under 공동대표 stays under it until it is finished")
                    .isEqualTo(ApprovalState.PARTIALLY_APPROVED);
            assertThat(reread.steps().get(1).requiredApprovals()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("회수 (recall)")
    class Recall {

        @BeforeEach
        void simpleLine() {
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)),
                    step(2, ApprovalStepKind.APPROVE, RoleExpression.account(drafter)));
        }

        @Test
        @DisplayName("the drafter may recall before anyone has approved")
        void recallBeforeFirstApproval() {
            ApprovalDocumentView view = submit();

            ApprovalDocumentView recalled = service.recall(world.principal(drafter),
                    view.document().id(), at(10), "금액을 잘못 적었습니다");

            assertThat(recalled.state()).isEqualTo(ApprovalState.RECALLED);
            assertThat(recalled.trail()).hasSize(1);
            assertThat(recalled.trail().get(0).action()).isEqualTo(ApprovalAction.RECALL);
        }

        @Test
        @DisplayName("a recall after the first approval is refused, and says what to do instead")
        void recallAfterFirstApprovalRefused() {
            ApprovalDocumentView view = submit();
            service.approve(world.principal(bujang), view.document().id(),
                    stepIdOf(view, ApprovalStepKind.APPROVE), at(10), null);

            assertThatThrownBy(() -> service.recall(world.principal(drafter),
                    view.document().id(), at(11), "역시 취소하겠습니다"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("already been approved")
                    .hasMessageContaining("return it instead");

            ApprovalDocumentView reread = documents.read(world.principal(drafter),
                    view.document().id());
            assertThat(reread.state())
                    .as("the refused recall left the approval that was really given intact")
                    .isEqualTo(ApprovalState.IN_PROGRESS);
            assertThat(reread.trail()).hasSize(1);
        }

        @Test
        @DisplayName("only the drafter may recall")
        void onlyDrafterMayRecall() {
            ApprovalDocumentView view = submit();
            world.grants.grantCompanyWide(ApprovalPermissions.DOCUMENT_WRITE, bujang);

            assertThatThrownBy(() -> service.recall(world.principal(bujang),
                    view.document().id(), at(10), "내가 대신 회수"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("Only the drafter");
        }
    }

    @Nested
    @DisplayName("대결 (acting approval)")
    class ActingApproval {

        private String standIn;

        @BeforeEach
        void twoGeneralManagers() {
            standIn = world.person("최부장", team, generalManager, LocalDate.of(2020, 1, 1));
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        }

        @Test
        @DisplayName("records whom the actor acted for, and never implies the absentee signed")
        void actingRecordsWhoItWasFor() {
            ApprovalDocumentView view = submit();
            String approveStep = stepIdOf(view, ApprovalStepKind.APPROVE);

            ApprovalDocumentView acted = service.actFor(world.principal(standIn),
                    view.document().id(), approveStep, bujang, at(10), "박부장 휴가로 대결합니다");

            assertThat(acted.state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(acted.trail()).hasSize(1);

            ApprovalActionEntity record = acted.trail().get(0);
            assertThat(record.action()).isEqualTo(ApprovalAction.ACTING);
            assertThat(record.actorAccountId()).isEqualTo(standIn);
            assertThat(record.actorDisplayName()).isEqualTo("최부장");
            assertThat(record.onBehalfOfAccountId()).isEqualTo(bujang);
            assertThat(record.comment()).isNotBlank();

            for (ApprovalActionEntity anyAction : acted.trail()) {
                assertThat(anyAction.actorAccountId())
                        .as("no row in the trail may be attributed to the absent approver; the "
                                + "record must never read as though 박부장 signed personally")
                        .isNotEqualTo(bujang);
                assertThat(anyAction.action())
                        .as("and 대결 is never recorded as an ordinary 승인")
                        .isNotEqualTo(ApprovalAction.APPROVE);
            }
        }

        @Test
        @DisplayName("acting for someone the document was never routed to is refused")
        void cannotActForAStranger() {
            ApprovalDocumentView view = submit();
            String approveStep = stepIdOf(view, ApprovalStepKind.APPROVE);

            assertThatThrownBy(() -> service.actFor(world.principal(standIn),
                    view.document().id(), approveStep, drafter, at(10), "대신합니다"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("not one of the approvers");
        }

        @Test
        @DisplayName("where absences are known, acting for someone at their desk is refused")
        void actingForSomeonePresentIsRefused() {
            ApprovalActionService checking = world.actionServiceKnowingAbsences(
                    new AbsenceDirectory() {
                        @Override
                        public boolean isAbsent(String accountId, LocalDate businessDate) {
                            return false;
                        }
                    });
            ApprovalDocumentView view = submit();

            assertThatThrownBy(() -> checking.actFor(world.principal(standIn),
                    view.document().id(), stepIdOf(view, ApprovalStepKind.APPROVE), bujang,
                    at(10), "대결합니다"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("not recorded as absent")
                    .hasMessageContaining("at their desk");
        }

        @Test
        @DisplayName("and permitted when they really are away")
        void actingForSomeoneAbsentIsAllowed() {
            ApprovalActionService checking = world.actionServiceKnowingAbsences(
                    new AbsenceDirectory() {
                        @Override
                        public boolean isAbsent(String accountId, LocalDate businessDate) {
                            return bujang.equals(accountId);
                        }
                    });
            ApprovalDocumentView view = submit();

            assertThat(checking.actFor(world.principal(standIn), view.document().id(),
                    stepIdOf(view, ApprovalStepKind.APPROVE), bujang, at(10), "휴가 중이라 대결")
                    .state())
                    .isEqualTo(ApprovalState.APPROVED);
        }

        @Test
        @DisplayName("대결 needs a reason, like every action that is not a plain 승인")
        void actingNeedsAReason() {
            ApprovalDocumentView view = submit();
            String approveStep = stepIdOf(view, ApprovalStepKind.APPROVE);

            assertThatThrownBy(() -> service.actFor(world.principal(standIn),
                    view.document().id(), approveStep, bujang, at(10), "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a reason");
        }
    }

    @Nested
    @DisplayName("전결 (delegated final approval)")
    class DelegatedFinal {

        @BeforeEach
        void bujangThenRepresentative() {
            world.person("대표일", team, representative, LocalDate.of(2010, 1, 1));
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)),
                    step(2, ApprovalStepKind.APPROVE, RoleExpression.representative()));
        }

        @Test
        @DisplayName("finalises the document and marks the remaining steps SKIPPED, not COMPLETED")
        void skippedStepsAreNotApprovals() {
            ApprovalDocumentView view = submit();
            String bujangStep = view.steps().get(1).id();

            ApprovalDocumentView finalised = service.delegateFinal(world.principal(bujang),
                    view.document().id(), bujangStep, at(10), "위임전결 규정 제5조에 따라 전결합니다");

            assertThat(finalised.state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(finalised.steps().get(1).state())
                    .isEqualTo(ApprovalStep.StepState.COMPLETED);
            assertThat(finalised.steps().get(2).state())
                    .as("nobody signed the 대표 step, and the record must not suggest otherwise")
                    .isEqualTo(ApprovalStep.StepState.SKIPPED);
            assertThat(finalised.trail()).hasSize(1);
        }

        @Test
        @DisplayName("전결 with nothing left to skip is refused as a mislabelled 승인")
        void delegatedFinalNeedsSomethingToSkip() {
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
            ApprovalDocumentView view = submit();

            assertThatThrownBy(() -> service.delegateFinal(world.principal(bujang),
                    view.document().id(), view.steps().get(1).id(), at(10), "전결"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("Use 승인");
        }
    }

    @Nested
    @DisplayName("반려 (return) and the trail")
    class ReturnAndTrail {

        @BeforeEach
        void simpleLine() {
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        }

        @Test
        @DisplayName("반려 returns to the drafter with the reason attached")
        void returnCarriesTheReason() {
            ApprovalDocumentView view = submit();

            ApprovalDocumentView returned = service.returnToDrafter(world.principal(bujang),
                    view.document().id(), view.steps().get(1).id(), at(10),
                    "영수증이 누락되었습니다");

            assertThat(returned.state()).isEqualTo(ApprovalState.RETURNED);
            assertThat(world.notifier.to(drafter))
                    .extracting(ApprovalNotification::kind)
                    .contains(ApprovalNotification.Kind.RETURNED);
            ApprovalNotification told = world.notifier.to(drafter)
                    .get(world.notifier.to(drafter).size() - 1);
            assertThat(told.bodyKo()).contains("영수증이 누락되었습니다");
        }

        @Test
        @DisplayName("반려 without a reason is refused before anything is written")
        void returnWithoutReasonRefused() {
            ApprovalDocumentView view = submit();

            assertThatThrownBy(() -> service.returnToDrafter(world.principal(bujang),
                    view.document().id(), view.steps().get(1).id(), at(10), null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(documents.read(world.principal(drafter), view.document().id()).state())
                    .isEqualTo(ApprovalState.IN_PROGRESS);
            assertThat(world.actions.all()).isEmpty();
        }

        @Test
        @DisplayName("every action is stamped with the document digest it was taken against")
        void actionsCarryTheSnapshotHash() {
            ApprovalDocumentView view = submit();
            String frozen = view.document().submittedSnapshotHash();

            ApprovalDocumentView approved = service.approve(world.principal(bujang),
                    view.document().id(), view.steps().get(1).id(), at(10), null);

            assertThat(approved.trail())
                    .extracting(ApprovalActionEntity::documentSnapshotHash)
                    .containsExactly(frozen);
        }

        @Test
        @DisplayName("actions are stamped in business time, and 26:00 sorts before the next day")
        void trailIsOrderedInBusinessTime() {
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)),
                    step(2, ApprovalStepKind.APPROVE, RoleExpression.account(drafter)));
            ApprovalDocumentView view = submit();

            // 박부장 approves at 26:00 — two in the morning, still the 30th.
            service.approve(world.principal(bujang), view.document().id(),
                    view.steps().get(1).id(), BusinessInstant.parse("2026-08-30T26:00:00.000"),
                    null);
            ApprovalDocumentView done = service.approve(world.principal(drafter),
                    view.document().id(), view.steps().get(2).id(),
                    BusinessInstant.parse("2026-08-31T-03:22:00.000"), null);

            assertThat(done.state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(done.trail())
                    .extracting(action -> action.actedAt().toWireString())
                    .as("date first, then offset: 26:00 on the 30th precedes -03:22 on the 31st, "
                            + "which lexicographic ordering of the wire string gets backwards")
                    .containsExactly("2026-08-30T26:00:00.000", "2026-08-31T-03:22:00.000");
        }
    }

    @Nested
    @DisplayName("consequences that must be part of the approval")
    class Outcomes {

        @BeforeEach
        void simpleLine() {
            useTemplate(step(0, ApprovalStepKind.DRAFT, RoleExpression.account(drafter)),
                    step(1, ApprovalStepKind.APPROVE,
                            RoleExpression.rank("BUJANG", RoleExpression.Domain.DRAFTER_UNIT)));
        }

        @Test
        @DisplayName("a listener runs exactly once when the document settles")
        void listenerRunsOnce() {
            RecordingListener listener = new RecordingListener();
            ApprovalActionService acting = world.actionService(listener);
            ApprovalDocumentView view = submit();

            acting.approve(world.principal(bujang), view.document().id(),
                    view.steps().get(1).id(), at(10), null);

            assertThat(listener.seen).hasSize(1);
            assertThat(listener.seen.get(0).state()).isEqualTo(ApprovalState.APPROVED);
            assertThat(listener.seen.get(0).documentType()).isEqualTo(EXPENSE);
            assertThat(listener.seen.get(0).amount()).isEqualByComparingTo(new BigDecimal("100000"));
            assertThat(listener.seen.get(0).actorAccountId()).isEqualTo(bujang);
        }

        @Test
        @DisplayName("a listener that refuses takes the whole approval with it — nothing is written")
        void refusingListenerWritesNothing() {
            ApprovalActionService acting = world.actionService(new FailingListener());
            ApprovalDocumentView view = submit();

            assertThatThrownBy(() -> acting.approve(world.principal(bujang),
                    view.document().id(), view.steps().get(1).id(), at(10), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("잔여 연차가 부족합니다");

            ApprovalDocumentView reread = documents.read(world.principal(drafter),
                    view.document().id());
            assertThat(reread.state())
                    .as("no approval was recorded, because its consequence could not be")
                    .isEqualTo(ApprovalState.IN_PROGRESS);
            assertThat(world.actions.all()).isEmpty();
            assertThat(reread.steps().get(1).state()).isEqualTo(ApprovalStep.StepState.PENDING);
        }

        @Test
        @DisplayName("a listener for another document type is not consulted")
        void listenersAreFilteredByType() {
            RecordingListener listener = new RecordingListener();
            listener.appliesTo = "LEAVE_REQUEST";
            ApprovalActionService acting = world.actionService(listener);
            ApprovalDocumentView view = submit();

            acting.approve(world.principal(bujang), view.document().id(),
                    view.steps().get(1).id(), at(10), null);

            assertThat(listener.seen).isEmpty();
        }

        @Test
        @DisplayName("a notifier that throws does not undo the approval it was reporting")
        void notifierFailureIsSwallowed() {
            ApprovalDocumentView view = submit();
            world.notifier.throwOnDelivery = true;

            ApprovalDocumentView approved = service.approve(world.principal(bujang),
                    view.document().id(), view.steps().get(1).id(), at(10), null);

            assertThat(approved.state())
                    .as("a courtesy channel being down is not a reason to lose a signature")
                    .isEqualTo(ApprovalState.APPROVED);
            assertThat(world.actions.all()).hasSize(1);
        }
    }

    private static final class RecordingListener implements ApprovalOutcomeListener {

        private final List<ApprovalOutcome> seen = new ArrayList<ApprovalOutcome>();
        private String appliesTo = EXPENSE;

        @Override
        public boolean appliesTo(String documentType) {
            return appliesTo.equals(documentType);
        }

        @Override
        public void documentSettled(ApprovalOutcome outcome) {
            seen.add(outcome);
        }
    }

    /** Stands in for a leave-balance write that cannot be honoured. */
    private static final class FailingListener implements ApprovalOutcomeListener {

        @Override
        public boolean appliesTo(String documentType) {
            return true;
        }

        @Override
        public void documentSettled(ApprovalOutcome outcome) {
            throw new IllegalStateException("잔여 연차가 부족합니다. (Balance will not cover this.)");
        }
    }
}
