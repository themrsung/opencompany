package com.coreintra.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.businesstime.BusinessInstant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The approval state machine.
 *
 * <p>Two of these are named acceptance tests from the brief and must never
 * regress: 공동대표 quorum is not satisfiable by a single approver, and a
 * document that has been approved cannot be recalled.
 */
class ApprovalLineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 30);
    private static final String DRAFTER = "acc-drafter";

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(DAY, hour, 0, 0);
    }

    private static Set<String> people(String... ids) {
        return new LinkedHashSet<String>(Arrays.asList(ids));
    }

    /** 기안 → 검토 → 결재, the ordinary shape. */
    private ApprovalLine simpleLine() {
        List<ApprovalStep> steps = new ArrayList<ApprovalStep>();
        steps.add(ApprovalStep.single("s0", 0, ApprovalStepKind.DRAFT, "drafter", DRAFTER));
        steps.add(ApprovalStep.single("s1", 1, ApprovalStepKind.REVIEW, "team lead", "acc-lead"));
        steps.add(ApprovalStep.single("s2", 2, ApprovalStepKind.APPROVE, "부장", "acc-bujang"));
        return new ApprovalLine("doc-1", DRAFTER, RepresentationMode.several(1), steps);
    }

    @Nested
    @DisplayName("the ordinary path")
    class HappyPath {

        @Test
        @DisplayName("advances step by step and completes")
        void advancesAndCompletes() {
            ApprovalLine line = simpleLine();
            assertThat(line.state()).isEqualTo(ApprovalState.DRAFTING);

            line.submit();
            assertThat(line.state()).isEqualTo(ApprovalState.IN_PROGRESS);
            assertThat(line.isAwaiting("acc-lead")).isTrue();
            assertThat(line.isAwaiting("acc-bujang"))
                    .as("a later approver is not yet expected to act")
                    .isFalse();

            line.act("s1", "acc-lead", "팀장", ApprovalAction.APPROVE, at(10), null, "hash-1", null);
            assertThat(line.isAwaiting("acc-bujang")).isTrue();

            line.act("s2", "acc-bujang", "부장", ApprovalAction.APPROVE, at(11), null, "hash-1", null);
            assertThat(line.state()).isEqualTo(ApprovalState.APPROVED);
        }

        @Test
        @DisplayName("참조 steps never block the line")
        void ccDoesNotBlock() {
            List<ApprovalStep> steps = new ArrayList<ApprovalStep>();
            steps.add(ApprovalStep.single("s0", 0, ApprovalStepKind.DRAFT, "drafter", DRAFTER));
            steps.add(ApprovalStep.single("s1", 1, ApprovalStepKind.CC, "회계팀", "acc-cc"));
            steps.add(ApprovalStep.single("s2", 2, ApprovalStepKind.APPROVE, "부장", "acc-bujang"));
            ApprovalLine line = new ApprovalLine("doc-cc", DRAFTER,
                    RepresentationMode.several(1), steps);

            line.submit();
            // The 참조 at position 1 must not park the document.
            assertThat(line.isAwaiting("acc-bujang")).isTrue();
            assertThat(line.isAwaiting("acc-cc")).isFalse();

            assertThatThrownBy(() -> line.act("s1", "acc-cc", "회계팀", ApprovalAction.APPROVE,
                    at(10), null, "h", null))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("참조 steps are for information");
        }

        @Test
        @DisplayName("parallel 합의 steps must all agree before the line moves on")
        void parallelConcurrenceBlocksUntilAllAgree() {
            List<ApprovalStep> steps = new ArrayList<ApprovalStep>();
            steps.add(ApprovalStep.single("s0", 0, ApprovalStepKind.DRAFT, "drafter", DRAFTER));
            steps.add(ApprovalStep.single("a", 1, ApprovalStepKind.CONCURRENCE, "법무", "acc-legal"));
            steps.add(ApprovalStep.single("b", 1, ApprovalStepKind.CONCURRENCE, "회계", "acc-fin"));
            steps.add(ApprovalStep.single("s2", 2, ApprovalStepKind.APPROVE, "대표", "acc-ceo"));
            ApprovalLine line = new ApprovalLine("doc-p", DRAFTER,
                    RepresentationMode.several(1), steps);

            line.submit();
            assertThat(line.isAwaiting("acc-legal")).isTrue();
            assertThat(line.isAwaiting("acc-fin")).isTrue();

            line.act("a", "acc-legal", "법무", ApprovalAction.APPROVE, at(10), null, "h", null);
            assertThat(line.isAwaiting("acc-ceo"))
                    .as("one of two concurrence peers has agreed; the line must not advance")
                    .isFalse();

            line.act("b", "acc-fin", "회계", ApprovalAction.APPROVE, at(11), null, "h", null);
            assertThat(line.isAwaiting("acc-ceo")).isTrue();
        }
    }

    @Nested
    @DisplayName("공동대표 quorum")
    class JointRepresentation {

        /** ACCEPTANCE: a joint quorum must not be satisfiable by one person. */
        @Test
        @DisplayName("one representative cannot satisfy a 2-of-3 quorum, however many times they act")
        void singleApproverCannotMeetQuorum() {
            RepresentationMode joint = RepresentationMode.joint(2, 3);
            List<ApprovalStep> steps = new ArrayList<ApprovalStep>();
            steps.add(ApprovalStep.single("s0", 0, ApprovalStepKind.DRAFT, "drafter", DRAFTER));
            steps.add(new ApprovalStep("rep", 1, ApprovalStepKind.APPROVE, "대표이사",
                    people("acc-ceo-1", "acc-ceo-2", "acc-ceo-3"), joint.requiredApprovals()));
            ApprovalLine line = new ApprovalLine("doc-j", DRAFTER, joint, steps);

            line.submit();
            line.act("rep", "acc-ceo-1", "대표 A", ApprovalAction.APPROVE, at(10), null, "h", null);

            assertThat(line.state())
                    .as("one of two required approvals: visibly partial, never approved")
                    .isEqualTo(ApprovalState.PARTIALLY_APPROVED);

            // The same representative acting again must not complete the quorum.
            assertThatThrownBy(() -> line.act("rep", "acc-ceo-1", "대표 A",
                    ApprovalAction.APPROVE, at(11), null, "h", null))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("distinct representatives");

            assertThat(line.state()).isEqualTo(ApprovalState.PARTIALLY_APPROVED);

            line.act("rep", "acc-ceo-2", "대표 B", ApprovalAction.APPROVE, at(12), null, "h", null);
            assertThat(line.state()).isEqualTo(ApprovalState.APPROVED);
        }

        @Test
        @DisplayName("a joint mode with a quorum of one is refused at construction")
        void quorumOfOneIsRefused() {
            assertThatThrownBy(() -> RepresentationMode.joint(1, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("각자대표 under another name");
        }

        @Test
        @DisplayName("a quorum larger than the number of representatives is refused")
        void impossibleQuorumRefused() {
            assertThatThrownBy(() -> RepresentationMode.joint(4, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be met");
        }

        @Test
        @DisplayName("각자대표: any one representative is sufficient and final")
        void severalRepresentationCompletesWithOne() {
            RepresentationMode several = RepresentationMode.several(3);
            List<ApprovalStep> steps = new ArrayList<ApprovalStep>();
            steps.add(ApprovalStep.single("s0", 0, ApprovalStepKind.DRAFT, "drafter", DRAFTER));
            steps.add(new ApprovalStep("rep", 1, ApprovalStepKind.APPROVE, "대표이사",
                    people("acc-ceo-1", "acc-ceo-2", "acc-ceo-3"), several.requiredApprovals()));
            ApprovalLine line = new ApprovalLine("doc-s", DRAFTER, several, steps);

            line.submit();
            line.act("rep", "acc-ceo-2", "대표 B", ApprovalAction.APPROVE, at(10), null, "h", null);
            assertThat(line.state()).isEqualTo(ApprovalState.APPROVED);
        }
    }

    @Nested
    @DisplayName("반려, 보류, 회수")
    class RejectionsAndWithdrawal {

        @Test
        @DisplayName("반려 requires a reason and returns the document")
        void returnRequiresReason() {
            ApprovalLine line = simpleLine();
            line.submit();

            assertThatThrownBy(() -> line.act("s1", "acc-lead", "팀장", ApprovalAction.RETURN,
                    at(10), "   ", "h", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a reason");

            line.act("s1", "acc-lead", "팀장", ApprovalAction.RETURN, at(10),
                    "증빙이 누락되었습니다.", "h", null);
            assertThat(line.state()).isEqualTo(ApprovalState.RETURNED);
            assertThat(line.state().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("보류 keeps the document at the step and is visibly held")
        void holdKeepsDocumentAtStep() {
            ApprovalLine line = simpleLine();
            line.submit();
            line.act("s1", "acc-lead", "팀장", ApprovalAction.HOLD, at(10),
                    "출장 복귀 후 확인하겠습니다.", "h", null);

            assertThat(line.state()).isEqualTo(ApprovalState.ON_HOLD);
            assertThat(line.state().isTerminal()).isFalse();

            line.act("s1", "acc-lead", "팀장", ApprovalAction.APPROVE, at(18), null, "h", null);
            assertThat(line.isAwaiting("acc-bujang")).isTrue();
        }

        /** ACCEPTANCE: 회수 only before the first approval. */
        @Test
        @DisplayName("회수 is refused once anyone has approved")
        void recallRefusedAfterApproval() {
            ApprovalLine line = simpleLine();
            line.submit();

            // Before any approval: permitted.
            ApprovalLine untouched = simpleLine();
            untouched.submit();
            untouched.recall(DRAFTER, "기안자", at(9), "잘못 올렸습니다.", "h");
            assertThat(untouched.state()).isEqualTo(ApprovalState.RECALLED);

            line.act("s1", "acc-lead", "팀장", ApprovalAction.APPROVE, at(10), null, "h", null);
            assertThatThrownBy(() -> line.recall(DRAFTER, "기안자", at(11), "취소", "h"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("already been approved");
        }

        @Test
        @DisplayName("only the drafter may recall")
        void onlyDrafterRecalls() {
            ApprovalLine line = simpleLine();
            line.submit();
            assertThatThrownBy(() -> line.recall("acc-lead", "팀장", at(10), "x", "h"))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("Only the drafter");
        }
    }

    @Nested
    @DisplayName("전결 and 대결")
    class DelegatedAndActing {

        @Test
        @DisplayName("전결 finalises the document and SKIPS the remaining steps")
        void delegatedFinalSkipsRest() {
            ApprovalLine line = simpleLine();
            line.submit();
            line.act("s1", "acc-lead", "팀장", ApprovalAction.DELEGATED_FINAL, at(10),
                    "전결 규정 제12조에 따름", "h", null);

            assertThat(line.state()).isEqualTo(ApprovalState.APPROVED);

            ApprovalStep skipped = line.steps().get(2);
            assertThat(skipped.state())
                    .as("nobody signed the 부장 step; the trail must not imply otherwise")
                    .isEqualTo(ApprovalStep.StepState.SKIPPED);
            assertThat(skipped.actions()).isEmpty();
        }

        @Test
        @DisplayName("대결 must record whom it was performed on behalf of")
        void actingRecordsPrincipal() {
            ApprovalLine line = simpleLine();
            line.submit();

            assertThatThrownBy(() -> line.act("s1", "acc-lead", "팀장", ApprovalAction.ACTING,
                    at(10), "부재중 대결", "h", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("implies the absent approver signed personally");

            line.act("s1", "acc-lead", "팀장", ApprovalAction.ACTING, at(10),
                    "부재중 대결", "h", "acc-absent");
            assertThat(line.trail().get(0).onBehalfOfAccountId()).isEqualTo("acc-absent");
            assertThat(line.trail().get(0).action()).isEqualTo(ApprovalAction.ACTING);
        }
    }

    @Nested
    @DisplayName("the trail")
    class Trail {

        @Test
        @DisplayName("orders by business time, date first, not by the wire string")
        void trailUsesBusinessOrdering() {
            ApprovalLine line = simpleLine();
            line.submit();
            // A shift-crossing approval at 26:00 on the 30th precedes one at
            // -02:00 on the 31st, even though the wall clock says otherwise.
            line.act("s1", "acc-lead", "팀장", ApprovalAction.APPROVE,
                    BusinessInstant.parse("2026-08-30T26:00:00.000"), null, "h", null);
            line.act("s2", "acc-bujang", "부장", ApprovalAction.APPROVE,
                    BusinessInstant.parse("2026-08-31T-02:00:00.000"), null, "h", null);

            List<ApprovalStep.ApprovalActionRecord> trail = line.trail();
            assertThat(trail).hasSize(2);
            assertThat(trail.get(0).actorAccountId()).isEqualTo("acc-lead");
            assertThat(trail.get(1).actorAccountId()).isEqualTo("acc-bujang");
        }

        @Test
        @DisplayName("every action carries the document snapshot hash it was taken against")
        void trailCarriesSnapshotHash() {
            ApprovalLine line = simpleLine();
            line.submit();
            line.act("s1", "acc-lead", "팀장", ApprovalAction.APPROVE, at(10), null, "sha256-abc", null);
            assertThat(line.trail().get(0).documentSnapshotHash()).isEqualTo("sha256-abc");
        }
    }

    @Nested
    @DisplayName("construction guards")
    class Construction {

        @Test
        @DisplayName("a line that can never complete is refused at construction")
        void unresolvableStepRefused() {
            assertThatThrownBy(() -> new ApprovalStep("x", 1, ApprovalStepKind.APPROVE,
                    "the 부장 of an empty unit", people(), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolved to no approver");

            assertThatThrownBy(() -> new ApprovalStep("x", 1, ApprovalStepKind.APPROVE,
                    "대표이사", people("acc-1"), 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("could never complete");
        }

        @Test
        @DisplayName("a line with no approving step is refused")
        void lineWithoutApproverRefused() {
            List<ApprovalStep> steps = new ArrayList<ApprovalStep>();
            steps.add(ApprovalStep.single("s0", 0, ApprovalStepKind.DRAFT, "drafter", DRAFTER));
            steps.add(ApprovalStep.single("s1", 1, ApprovalStepKind.CC, "참조", "acc-cc"));

            assertThatThrownBy(() -> new ApprovalLine("doc", DRAFTER,
                    RepresentationMode.several(1), steps))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("approves itself");
        }

        @Test
        @DisplayName("an approved document cannot be acted on again")
        void terminalIsTerminal() {
            ApprovalLine line = simpleLine();
            line.submit();
            line.act("s1", "acc-lead", "팀장", ApprovalAction.APPROVE, at(10), null, "h", null);
            line.act("s2", "acc-bujang", "부장", ApprovalAction.APPROVE, at(11), null, "h", null);

            assertThatThrownBy(() -> line.act("s2", "acc-bujang", "부장",
                    ApprovalAction.RETURN, at(12), "생각이 바뀌었습니다", "h", null))
                    .isInstanceOf(ApprovalLine.ApprovalRuleException.class)
                    .hasMessageContaining("can no longer be acted on");
        }
    }
}
