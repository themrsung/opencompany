package com.coreintra.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Straight-line amortisation.
 *
 * <p>The named acceptance test from the brief - a schedule sums to base minus residual exactly -
 * is general over amounts, months and rounding digits and lives in
 * {@code AmortizationScheduleProperties}. These are the cases where the exact shape matters.
 */
class AmortizationScheduleTest {

    private static final YearMonth JANUARY = YearMonth.of(2026, 1);

    @Nested
    @DisplayName("the remainder lands on one line and is never dropped")
    class RemainderPlacement {

        @Test
        @DisplayName("an indivisible amount puts the odd sub-unit at the end by default use")
        void remainderAtTheEnd() {
            AmortizationSchedule schedule = AmortizationSchedule.straightLine(
                    Amount.parse("1000000"), Amount.ZERO, JANUARY, 3, 2,
                    AmortizationSchedule.Remainder.END, 28);

            List<AmortizationSchedule.Instalment> lines = schedule.instalments();
            assertThat(lines).hasSize(3);
            assertThat(lines.get(0).amount().toExactString()).isEqualTo("333333.33");
            assertThat(lines.get(1).amount().toExactString()).isEqualTo("333333.33");
            assertThat(lines.get(2).amount().toExactString()).isEqualTo("333333.34");
            assertThat(schedule.total()).isEqualTo(Amount.parse("1000000"));
        }

        @Test
        @DisplayName("or at the start, when the policy is to recognise it up front")
        void remainderAtTheStart() {
            AmortizationSchedule schedule = AmortizationSchedule.straightLine(
                    Amount.parse("1000000"), Amount.ZERO, JANUARY, 3, 2,
                    AmortizationSchedule.Remainder.START, 28);

            assertThat(schedule.instalments().get(0).amount().toExactString()).isEqualTo("333333.34");
            assertThat(schedule.instalments().get(2).amount().toExactString()).isEqualTo("333333.33");
            assertThat(schedule.total()).isEqualTo(Amount.parse("1000000"));
        }

        @Test
        @DisplayName("a schedule that divides evenly has no remainder to place")
        void evenSchedule() {
            AmortizationSchedule start = AmortizationSchedule.straightLine(Amount.parse("1200000"),
                    Amount.ZERO, JANUARY, 12, 0, AmortizationSchedule.Remainder.START, 1);
            AmortizationSchedule end = AmortizationSchedule.straightLine(Amount.parse("1200000"),
                    Amount.ZERO, JANUARY, 12, 0, AmortizationSchedule.Remainder.END, 1);

            assertThat(start.instalments().get(0).amount()).isEqualTo(Amount.parse("100000"));
            assertThat(end.instalments().get(11).amount()).isEqualTo(Amount.parse("100000"));
        }
    }

    @Nested
    @DisplayName("residual value")
    class Residual {

        @Test
        @DisplayName("the carrying amount ends at exactly the residual, not near it")
        void carryingAmountEndsAtResidual() {
            AmortizationSchedule schedule = AmortizationSchedule.straightLine(
                    Amount.parse("10000000"), Amount.parse("1000000"), JANUARY, 7, 0,
                    AmortizationSchedule.Remainder.END, 28);

            assertThat(schedule.total()).isEqualTo(Amount.parse("9000000"));
            assertThat(schedule.instalments().get(6).carryingAmount())
                    .as("what is left on the books when the schedule finishes")
                    .isEqualTo(Amount.parse("1000000"));
        }

        @Test
        @DisplayName("nothing to amortise is refused rather than producing a year of zeroes")
        void nothingToAmortise() {
            assertThatThrownBy(() -> AmortizationSchedule.straightLine(Amount.parse("500000"),
                    Amount.parse("500000"), JANUARY, 12, 0,
                    AmortizationSchedule.Remainder.END, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nothing to amortise");
        }

        @Test
        @DisplayName("a credit balance amortises too, and still sums exactly")
        void deferredRevenueAmortisesNegatively() {
            // Deferred revenue released into income over a year: the same arithmetic with the
            // other sign, which is why nothing here assumes a positive amount.
            AmortizationSchedule schedule = AmortizationSchedule.straightLine(
                    Amount.parse("-1000000"), Amount.ZERO, JANUARY, 3, 2,
                    AmortizationSchedule.Remainder.END, 28);

            assertThat(schedule.total()).isEqualTo(Amount.parse("-1000000"));
            assertThat(schedule.instalments().get(2).amount().toExactString()).isEqualTo("-333333.34");
        }
    }

    @Nested
    @DisplayName("dates")
    class Dates {

        @Test
        @DisplayName("a posting day beyond the month's length becomes month end, not the next month")
        void shortMonthsClamp() {
            AmortizationSchedule schedule = AmortizationSchedule.straightLine(
                    Amount.parse("300"), Amount.ZERO, JANUARY, 3, 0,
                    AmortizationSchedule.Remainder.END, 31);

            assertThat(schedule.instalments().get(0).businessDate()).isEqualTo(LocalDate.of(2026, 1, 31));
            assertThat(schedule.instalments().get(1).businessDate())
                    .as("February has no 31st, and skipping the month would be worse")
                    .isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(schedule.instalments().get(2).businessDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName("instalments are numbered from one and run consecutively")
        void numbering() {
            AmortizationSchedule schedule = AmortizationSchedule.straightLine(
                    Amount.parse("1200"), Amount.ZERO, YearMonth.of(2026, 11), 3, 0,
                    AmortizationSchedule.Remainder.END, 15);

            assertThat(schedule.instalments().get(0).number()).isEqualTo(1);
            assertThat(schedule.instalments().get(2).number()).isEqualTo(3);
            assertThat(schedule.instalments().get(2).businessDate())
                    .as("the schedule runs over a year boundary without restarting")
                    .isEqualTo(LocalDate.of(2027, 1, 15));
        }
    }

    @Nested
    @DisplayName("arguments that would produce a meaningless schedule are refused")
    class Refusals {

        @Test
        @DisplayName("zero months")
        void zeroMonths() {
            assertThatThrownBy(() -> AmortizationSchedule.straightLine(Amount.parse("100"),
                    Amount.ZERO, JANUARY, 0, 0, AmortizationSchedule.Remainder.END, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one month");
        }

        @Test
        @DisplayName("a negative rounding setting")
        void negativeRounding() {
            assertThatThrownBy(() -> AmortizationSchedule.straightLine(Amount.parse("100"),
                    Amount.ZERO, JANUARY, 2, -1, AmortizationSchedule.Remainder.END, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("roundingDigits");
        }

        @Test
        @DisplayName("a posting day that no month has")
        void impossiblePostingDay() {
            assertThatThrownBy(() -> AmortizationSchedule.straightLine(Amount.parse("100"),
                    Amount.ZERO, JANUARY, 2, 0, AmortizationSchedule.Remainder.END, 32))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("postingDay");
        }
    }

    @Test
    @DisplayName("rounding digits are the schedule's own decision, not the currency's")
    void roundingDigitsAreIndependentOfDisplay() {
        // KRW displays no decimals. A KRW schedule may still run at four, because the contract
        // says so, and the stored instalments keep every digit.
        AmortizationSchedule schedule = AmortizationSchedule.straightLine(Amount.parse("1000"),
                Amount.ZERO, JANUARY, 3, 4, AmortizationSchedule.Remainder.END, 1);

        assertThat(schedule.instalments().get(0).amount().toExactString()).isEqualTo("333.3333");
        assertThat(Currency.krw().formatForDisplay(schedule.instalments().get(0).amount()))
                .as("displayed rounded")
                .isEqualTo("333");
        assertThat(schedule.instalments().get(0).amount().toExactString())
                .as("stored unchanged by having been displayed")
                .isEqualTo("333.3333");
        assertThat(schedule.total()).isEqualTo(Amount.parse("1000"));
    }
}
