package com.coreintra.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ApprovalLineTemplateTest {

    /** 지출결의서: base line, plus an 이사 above 5,000,000원, plus 대표 above 50,000,000원. */
    private ApprovalLineTemplate expenseTemplate() {
        List<ApprovalLineTemplate.TemplateStep> base = Arrays.asList(
                new ApprovalLineTemplate.TemplateStep(0, ApprovalStepKind.DRAFT,
                        RoleExpression.parse("account:DRAFTER"), false),
                new ApprovalLineTemplate.TemplateStep(1, ApprovalStepKind.REVIEW,
                        RoleExpression.parse("rank:과장@DRAFTER_UNIT"), false),
                new ApprovalLineTemplate.TemplateStep(2, ApprovalStepKind.APPROVE,
                        RoleExpression.parse("rank:부장@DRAFTER_UNIT_PARENT"), false));

        List<ApprovalLineTemplate.ThresholdRule> rules = Arrays.asList(
                new ApprovalLineTemplate.ThresholdRule(new BigDecimal("5000000"),
                        Arrays.asList(new ApprovalLineTemplate.TemplateStep(3,
                                ApprovalStepKind.APPROVE,
                                RoleExpression.parse("rank:이사@COMPANY"), false)),
                        "5,000,000원 이상 지출은 이사 결재가 필요합니다."),
                new ApprovalLineTemplate.ThresholdRule(new BigDecimal("50000000"),
                        Arrays.asList(new ApprovalLineTemplate.TemplateStep(4,
                                ApprovalStepKind.APPROVE,
                                RoleExpression.representative(), false)),
                        "50,000,000원 이상 지출은 대표이사 결재가 필요합니다."));

        return new ApprovalLineTemplate("t-expense", "지출결의서", "unit-finance",
                new ArrayList<ApprovalLineTemplate.TemplateStep>(base),
                new ArrayList<ApprovalLineTemplate.ThresholdRule>(rules));
    }

    @Nested
    @DisplayName("amount thresholds")
    class Thresholds {

        @Test
        @DisplayName("a small expense gets only the base line")
        void belowAllThresholds() {
            assertThat(expenseTemplate().stepsFor(new BigDecimal("300000"))).hasSize(3);
        }

        @Test
        @DisplayName("thresholds are inclusive at exactly the boundary")
        void inclusiveAtBoundary() {
            // Exclusive bounds produce the classic off-by-one where the round
            // number everyone tests with falls through the gap.
            assertThat(expenseTemplate().stepsFor(new BigDecimal("5000000")))
                    .as("exactly 5,000,000 must attract the 이사 step")
                    .hasSize(4);
            assertThat(expenseTemplate().stepsFor(new BigDecimal("4999999"))).hasSize(3);
        }

        @Test
        @DisplayName("scale does not change the decision: 5000000.00 behaves as 5000000")
        void comparisonIsNumericNotEquals() {
            assertThat(expenseTemplate().stepsFor(new BigDecimal("5000000.00"))).hasSize(4);
            assertThat(expenseTemplate().stepsFor(new BigDecimal("5.0E+6"))).hasSize(4);
        }

        @Test
        @DisplayName("thresholds stack rather than replacing one another")
        void thresholdsAreAdditive() {
            // A 60,000,000원 expense keeps the 이사 step AND adds the 대표. If
            // the higher rule replaced the lower one, it would silently remove a
            // signature that the lower threshold required.
            List<ApprovalLineTemplate.TemplateStep> steps =
                    expenseTemplate().stepsFor(new BigDecimal("60000000"));
            assertThat(steps).hasSize(5);
            assertThat(steps.get(3).role().value()).isEqualTo("이사");
            assertThat(steps.get(4).role().selector())
                    .isEqualTo(RoleExpression.Selector.REPRESENTATIVE);
        }

        @Test
        @DisplayName("a document with no amount gets the base line")
        void nullAmountIsBaseLine() {
            assertThat(expenseTemplate().stepsFor(null)).hasSize(3);
        }

        @Test
        @DisplayName("the drafter is told why the extra steps are there")
        void explainsItself() {
            assertThat(expenseTemplate().explainFor(new BigDecimal("60000000")))
                    .hasSize(2)
                    .anyMatch(reason -> reason.contains("이사 결재"))
                    .anyMatch(reason -> reason.contains("대표이사 결재"));
            assertThat(expenseTemplate().explainFor(new BigDecimal("1000"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("role expressions")
    class RoleExpressions {

        @Test
        @DisplayName("round-trip through the canonical text form")
        void roundTrip() {
            String[] expressions = {
                    "rank:부장@DRAFTER_UNIT",
                    "rank:이사@COMPANY",
                    "jobFunction:회계@DRAFTER_UNIT_SUBTREE",
                    "permission:finance.expense:approve@COMPANY",
                    "representative@COMPANY",
                    "account:acc-123",
            };
            for (String text : expressions) {
                assertThat(RoleExpression.parse(text).toString()).isEqualTo(text);
            }
        }

        @Test
        @DisplayName("a permission expression keeps its own colon")
        void permissionKeepsItsColon() {
            RoleExpression parsed = RoleExpression.parse("permission:finance.expense:approve@COMPANY");
            assertThat(parsed.selector()).isEqualTo(RoleExpression.Selector.PERMISSION);
            assertThat(parsed.value()).isEqualTo("finance.expense:approve");
        }

        @Test
        @DisplayName("an unparseable expression says what the grammar is")
        void parseErrorsAreActionable() {
            assertThatThrownBy(() -> RoleExpression.parse("부장의 상사"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected");
            assertThatThrownBy(() -> RoleExpression.parse("rank:부장@NOWHERE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown domain");
            assertThatThrownBy(() -> RoleExpression.parse("grade:부장@COMPANY"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown selector");
        }

        @Test
        @DisplayName("a missing domain defaults to the company")
        void defaultDomain() {
            assertThat(RoleExpression.parse("rank:부장").domain())
                    .isEqualTo(RoleExpression.Domain.COMPANY);
        }
    }
}
