package com.coreintra.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * ACCEPTANCE: an amortisation schedule sums to base minus residual, exactly.
 *
 * <p>A property rather than examples, because the invariant is general: it has to hold for any
 * amount, any number of months and any rounding setting, and the interesting failures are the
 * combinations nobody would think to write down. Three months at two decimals is the case everyone
 * checks; 97 months at five decimals with a residual is the one that drifts.
 *
 * <p>Drift here is not a display bug. A schedule that loses 0.01 leaves a cent on the balance sheet
 * for the life of the asset, and someone eventually writes it off with an entry that explains
 * nothing.
 */
class AmortizationScheduleProperties {

    private static final YearMonth START = YearMonth.of(2026, 1);

    @Property(tries = 2000)
    void scheduleSumsToBaseMinusResidualExactly(
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long baseUnits,
            @ForAll @IntRange(min = 0, max = 6) int baseScale,
            @ForAll @IntRange(min = 0, max = 100) int residualPercent,
            @ForAll @IntRange(min = 1, max = 120) int months,
            @ForAll @IntRange(min = 0, max = 6) int roundingDigits,
            @ForAll boolean remainderAtStart,
            @ForAll @IntRange(min = 1, max = 31) int postingDay) {

        Amount base = Amount.of(BigDecimal.valueOf(baseUnits, baseScale));
        Amount residual = residualOf(base, residualPercent);
        Amount amortisable = base.subtract(residual);
        Assume.that(!amortisable.isZero());

        AmortizationSchedule schedule = AmortizationSchedule.straightLine(base, residual, START,
                months, roundingDigits, remainderAtStart
                        ? AmortizationSchedule.Remainder.START
                        : AmortizationSchedule.Remainder.END,
                postingDay);

        assertThat(schedule.instalments()).hasSize(months);
        assertThat(schedule.total())
                .as("the schedule must neither lose nor invent an amount")
                .isEqualTo(amortisable);
    }

    @Property(tries = 1000)
    void theCarryingAmountEndsAtTheResidual(
            @ForAll @LongRange(min = 1L, max = 1_000_000_000_000L) long baseUnits,
            @ForAll @IntRange(min = 0, max = 4) int baseScale,
            @ForAll @IntRange(min = 0, max = 90) int residualPercent,
            @ForAll @IntRange(min = 1, max = 60) int months,
            @ForAll @IntRange(min = 0, max = 4) int roundingDigits) {

        Amount base = Amount.of(BigDecimal.valueOf(baseUnits, baseScale));
        Amount residual = residualOf(base, residualPercent);
        Assume.that(!base.subtract(residual).isZero());

        List<AmortizationSchedule.Instalment> lines = AmortizationSchedule.straightLine(base, residual,
                START, months, roundingDigits, AmortizationSchedule.Remainder.END, 28).instalments();

        assertThat(lines.get(lines.size() - 1).carryingAmount())
                .as("what is left on the books when the last instalment is posted")
                .isEqualTo(residual);
    }

    @Property(tries = 1000)
    void whereTheRemainderGoesDoesNotChangeTheTotal(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long baseUnits,
            @ForAll @IntRange(min = 0, max = 6) int baseScale,
            @ForAll @IntRange(min = 1, max = 120) int months,
            @ForAll @IntRange(min = 0, max = 6) int roundingDigits) {

        Amount base = Amount.of(BigDecimal.valueOf(baseUnits, baseScale));
        Assume.that(!base.isZero());

        AmortizationSchedule front = AmortizationSchedule.straightLine(base, Amount.ZERO, START,
                months, roundingDigits, AmortizationSchedule.Remainder.START, 1);
        AmortizationSchedule back = AmortizationSchedule.straightLine(base, Amount.ZERO, START,
                months, roundingDigits, AmortizationSchedule.Remainder.END, 1);

        assertThat(front.total()).isEqualTo(back.total());
        assertThat(front.total()).isEqualTo(base);
    }

    @Property(tries = 1000)
    void everyInstalmentIsOnTheSameSideAsTheAmountBeingAmortised(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long baseUnits,
            @ForAll @IntRange(min = 1, max = 36) int months,
            @ForAll @IntRange(min = 2, max = 6) int roundingDigits) {

        Amount base = Amount.of(BigDecimal.valueOf(baseUnits, 2));
        Assume.that(!base.isZero());
        // Enough magnitude that no instalment rounds away to nothing, which is a legitimate
        // outcome the caller is told about rather than a sign error.
        Assume.that(base.value().abs().compareTo(BigDecimal.valueOf((long) months)) > 0);

        AmortizationSchedule schedule = AmortizationSchedule.straightLine(base, Amount.ZERO, START,
                months, roundingDigits, AmortizationSchedule.Remainder.END, 15);

        for (AmortizationSchedule.Instalment line : schedule.instalments()) {
            assertThat(line.amount().signum())
                    .as("instalment %s of a %s schedule", line.number(), base.toExactString())
                    .isEqualTo(base.signum());
        }
    }

    /** A residual on the same side as the base and never larger than it. */
    private static Amount residualOf(Amount base, int percent) {
        BigDecimal fraction = base.value()
                .multiply(BigDecimal.valueOf((long) percent))
                .divide(BigDecimal.valueOf(100L), base.value().scale() + 4,
                        java.math.RoundingMode.DOWN);
        return Amount.of(fraction);
    }
}
