package com.coreintra.approval.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 취업규칙, and the invariant that must never regress.
 *
 * <p>ACCEPTANCE (from the brief): employment rules cannot be changed without
 * representative approval, <b>by any path including the API and a master
 * account</b>. These tests assert that by construction: there is no other way
 * to produce an effective version, so there is no path to test that could
 * bypass it.
 */
class EmploymentRulesTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 9, 1);

    private static List<EmploymentRules.Section> sections(String... numbers) {
        List<EmploymentRules.Section> list = new ArrayList<EmploymentRules.Section>();
        for (String number : numbers) {
            list.add(new EmploymentRules.Section(number, "제목 " + number, "Heading " + number,
                    "본문 " + number, "Body " + number));
        }
        return list;
    }

    private static EmploymentRules publishUnder(RepresentationMode mode, ApprovalState state,
            String... approvers) {
        return EmploymentRules.publish("er-1", "c-1", 1, EFFECTIVE, sections("제1조", "제2조"),
                "doc-1", state, mode, Arrays.asList(approvers));
    }

    @Nested
    @DisplayName("the representative-approval invariant")
    class Invariant {

        @Test
        @DisplayName("no approval document means no employment rules, for anyone")
        void approvalDocumentIsMandatory() {
            assertThatThrownBy(() -> EmploymentRules.publish("er-1", "c-1", 1, EFFECTIVE,
                    sections("제1조"), null, ApprovalState.APPROVED,
                    RepresentationMode.several(1), Arrays.asList("rep-1")))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("대표자 결재가 필요합니다")
                    .hasMessageContaining("master account does not have one either");
        }

        @Test
        @DisplayName("there is no constructor, setter or back door that skips the check")
        void noBypassExists() {
            // The invariant is structural, not procedural. If a second way to
            // build one of these is ever added, this test is where the argument
            // should happen.
            assertThat(EmploymentRules.class.getConstructors())
                    .as("EmploymentRules must have no public constructor; publish() is the only "
                            + "way to produce an effective version")
                    .isEmpty();

            java.lang.reflect.Method[] methods = EmploymentRules.class.getMethods();
            for (java.lang.reflect.Method method : methods) {
                assertThat(method.getName())
                        .as("no setter may exist: a published version is immutable")
                        .doesNotStartWith("set");
            }
        }

        @Test
        @DisplayName("an approval that is still in progress does not count")
        void inProgressIsNotApproved() {
            assertThatThrownBy(() -> publishUnder(
                    RepresentationMode.several(1), ApprovalState.IN_PROGRESS, "rep-1"))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("IN_PROGRESS");
        }

        @Test
        @DisplayName("a returned or recalled approval does not count either")
        void terminalNonApprovalsRejected() {
            for (ApprovalState state : new ApprovalState[] {
                    ApprovalState.RETURNED, ApprovalState.RECALLED, ApprovalState.ON_HOLD,
                    ApprovalState.DRAFTING }) {
                assertThatThrownBy(() -> publishUnder(
                        RepresentationMode.several(1), state, "rep-1"))
                        .as("state %s must not publish employment rules", state)
                        .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class);
            }
        }
    }

    @Nested
    @DisplayName("공동대표 quorum")
    class JointRepresentation {

        @Test
        @DisplayName("a partially approved joint document is refused, and says why")
        void partialQuorumRefused() {
            assertThatThrownBy(() -> publishUnder(
                    RepresentationMode.joint(2, 3), ApprovalState.PARTIALLY_APPROVED, "rep-1"))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("quorum that is partially met is not met");
        }

        @Test
        @DisplayName("one representative cannot satisfy a 2-of-3 quorum by appearing twice")
        void duplicateApproverDoesNotCount() {
            // Even with the approval document marked APPROVED, the representative
            // list is checked for DISTINCT people here as well. Two independent
            // guards, because this is the invariant that protects every other one.
            assertThatThrownBy(() -> publishUnder(RepresentationMode.joint(2, 3),
                    ApprovalState.APPROVED, "rep-1", "rep-1"))
                    .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                    .hasMessageContaining("one person approving twice does not satisfy a quorum");
        }

        @Test
        @DisplayName("two distinct representatives do satisfy a 2-of-3 quorum")
        void distinctApproversSatisfyQuorum() {
            EmploymentRules rules = publishUnder(RepresentationMode.joint(2, 3),
                    ApprovalState.APPROVED, "rep-1", "rep-2");
            assertThat(rules.approvingRepresentativeIds()).containsExactly("rep-1", "rep-2");
            assertThat(rules.approvedUnderMode().isJoint()).isTrue();
        }

        @Test
        @DisplayName("각자대표: one representative is sufficient")
        void severalRepresentationNeedsOne() {
            assertThat(publishUnder(RepresentationMode.several(3), ApprovalState.APPROVED, "rep-2")
                    .approvingRepresentativeIds()).containsExactly("rep-2");
        }

        @Test
        @DisplayName("the mode in force AT APPROVAL is recorded, not the mode in force now")
        void modeIsSnapshotted() {
            // A company that later switches to 각자대표 must not retroactively
            // make a previously insufficient approval look sufficient.
            EmploymentRules rules = publishUnder(RepresentationMode.joint(2, 2),
                    ApprovalState.APPROVED, "rep-1", "rep-2");
            assertThat(rules.approvedUnderMode().requiredApprovals()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("versioning and effective dates")
    class Versioning {

        @Test
        @DisplayName("employees see the version effective on a given date")
        void effectiveOn() {
            EmploymentRules rules = publishUnder(
                    RepresentationMode.several(1), ApprovalState.APPROVED, "rep-1");
            assertThat(rules.isEffectiveOn(EFFECTIVE.minusDays(1))).isFalse();
            assertThat(rules.isEffectiveOn(EFFECTIVE)).isTrue();
            assertThat(rules.isEffectiveOn(EFFECTIVE.plusYears(3))).isTrue();
        }

        @Test
        @DisplayName("an effective date is mandatory")
        void effectiveDateRequired() {
            assertThatThrownBy(() -> EmploymentRules.publish("er", "c", 1, null,
                    sections("제1조"), "doc", ApprovalState.APPROVED,
                    RepresentationMode.several(1), Arrays.asList("rep-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("which version applied on any given day");
        }

        @Test
        @DisplayName("a section without Korean text is refused")
        void koreanIsAuthoritative() {
            assertThatThrownBy(() -> new EmploymentRules.Section(
                    "제1조", "Heading", "Heading", null, "English only"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authoritative");
        }
    }

    @Nested
    @DisplayName("section-level diff")
    class Diff {

        private EmploymentRules version(int number, List<EmploymentRules.Section> sections) {
            return EmploymentRules.publish("er-" + number, "c-1", number, EFFECTIVE, sections,
                    "doc-" + number, ApprovalState.APPROVED, RepresentationMode.several(1),
                    Arrays.asList("rep-1"));
        }

        @Test
        @DisplayName("reports added, amended, removed and unchanged sections")
        void reportsEveryKindOfChange() {
            List<EmploymentRules.Section> before = sections("제1조", "제2조", "제3조");
            List<EmploymentRules.Section> after = new ArrayList<EmploymentRules.Section>();
            after.add(before.get(0));
            after.add(new EmploymentRules.Section("제2조", "제목 제2조", "Heading 제2조",
                    "개정된 본문입니다.", "Amended body"));
            after.add(new EmploymentRules.Section("제4조", "신설", "New", "신설 조항", "New clause"));

            List<EmploymentRules.SectionDiff> diffs =
                    version(2, after).diffAgainst(version(1, before));

            assertThat(diffs).hasSize(4);
            assertThat(diffs.get(0).kind())
                    .isEqualTo(EmploymentRules.SectionDiff.ChangeKind.UNCHANGED);
            assertThat(diffs.get(1).kind())
                    .isEqualTo(EmploymentRules.SectionDiff.ChangeKind.AMENDED);
            assertThat(diffs.get(2).kind())
                    .isEqualTo(EmploymentRules.SectionDiff.ChangeKind.ADDED);
            assertThat(diffs.get(3).kind())
                    .as("a repealed section must appear in the diff, not vanish from it")
                    .isEqualTo(EmploymentRules.SectionDiff.ChangeKind.REMOVED);
            assertThat(diffs.get(3).sectionNumber()).isEqualTo("제3조");
        }

        @Test
        @DisplayName("a change in only the English text still counts as an amendment")
        void englishOnlyChangeIsAnAmendment() {
            // The English version is what an overseas subsidiary's staff read.
            // Treating a change to it as cosmetic would let it drift from the
            // Korean without review.
            List<EmploymentRules.Section> before = new ArrayList<EmploymentRules.Section>();
            before.add(new EmploymentRules.Section("제1조", "목적", "Purpose", "본문", "Old English"));
            List<EmploymentRules.Section> after = new ArrayList<EmploymentRules.Section>();
            after.add(new EmploymentRules.Section("제1조", "목적", "Purpose", "본문", "New English"));

            assertThat(version(2, after).substantiveChangesAgainst(version(1, before)))
                    .hasSize(1);
        }

        @Test
        @DisplayName("the first version diffs cleanly against nothing")
        void firstVersionAgainstNull() {
            List<EmploymentRules.SectionDiff> diffs = version(1, sections("제1조")).diffAgainst(null);
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).kind())
                    .isEqualTo(EmploymentRules.SectionDiff.ChangeKind.ADDED);
        }

        @Test
        @DisplayName("substantiveChanges omits unchanged sections")
        void substantiveOnly() {
            List<EmploymentRules.Section> same = sections("제1조", "제2조");
            assertThat(version(2, same).substantiveChangesAgainst(version(1, same))).isEmpty();
        }
    }
}
