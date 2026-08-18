package com.coreintra.accounting.domain;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * A straight-line amortisation schedule: what to recognise, and on which business day.
 *
 * <h2>The schedule sums to the base amount exactly</h2>
 *
 * <p>Dividing 1,000,000 over three months at two decimals leaves 0.01 unaccounted for. A
 * schedule that ignores it amortises 999,999.99 of a 1,000,000 asset and leaves a cent sitting
 * on the balance sheet forever, which someone will eventually write off with an entry that
 * explains nothing. So the remainder is not dropped and it is not spread: it is placed on one
 * line, at the {@link Remainder#START start} or the {@link Remainder#END end}, and the choice is
 * the caller's because it is a policy question, not an arithmetic one. The total of the lines
 * equals base minus residual to the last digit, always, and that is asserted here rather than
 * hoped for.
 *
 * <h2>Rounding digits are not the currency's display decimals</h2>
 *
 * <p>This is the one place in the module where a rounding decision is legitimate, because it is
 * being made <em>before</em> anything is written: the caller is deciding what the instalments
 * should be, not truncating a figure that already exists. Nothing here reads
 * {@code display_decimals}; a KRW schedule may perfectly well run at four decimals if that is
 * what the contract says.
 *
 * <h2>Compute and preview only</h2>
 *
 * <p>This produces figures and dates. It posts nothing. Entries are built from a schedule, shown
 * to a person, and posted by that person - and the parameters are then stored beside the batch as
 * lineage that is never re-executed.
 */
public final class AmortizationSchedule implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Which line carries the indivisible remainder. */
    public enum Remainder {

        /** Front-loaded: the first period absorbs it. */
        START,

        /** Back-loaded: the last period clears the balance to exactly the residual. */
        END
    }

    private final Amount base;
    private final Amount residual;
    private final int months;
    private final int roundingDigits;
    private final Remainder remainderTo;
    private final List<Line> lines;

    private AmortizationSchedule(Amount base, Amount residual, int months, int roundingDigits,
            Remainder remainderTo, List<Line> lines) {
        this.base = base;
        this.residual = residual;
        this.months = months;
        this.roundingDigits = roundingDigits;
        this.remainderTo = remainderTo;
        this.lines = Immutables.copyOf(lines);
    }

    /**
     * Builds the schedule.
     *
     * @param base what is being amortised
     * @param residual what is left at the end; {@link Amount#ZERO} for the ordinary case
     * @param startMonth the month of the first instalment
     * @param months how many instalments, at least one
     * @param roundingDigits decimals for each instalment; the remainder keeps what this drops
     * @param remainderTo which line absorbs the remainder
     * @param postingDay day of month for each instalment, clamped to short months so that 31
     *     means "month end" rather than "skip February"
     * @throws IllegalArgumentException if there is nothing to amortise, or an argument is outside
     *     the range where the result would still mean something
     */
    public static AmortizationSchedule straightLine(Amount base, Amount residual,
            YearMonth startMonth, int months, int roundingDigits, Remainder remainderTo,
            int postingDay) {
        if (base == null || residual == null) {
            throw new IllegalArgumentException("a schedule needs a base amount and a residual");
        }
        if (startMonth == null) {
            throw new IllegalArgumentException("a schedule needs a starting month");
        }
        if (remainderTo == null) {
            throw new IllegalArgumentException(
                    "a schedule needs to know which line takes the remainder");
        }
        if (months < 1) {
            throw new IllegalArgumentException("a schedule needs at least one month, got " + months);
        }
        if (roundingDigits < 0 || roundingDigits > 18) {
            throw new IllegalArgumentException(
                    "roundingDigits must be 0-18, got " + roundingDigits);
        }
        if (postingDay < 1 || postingDay > 31) {
            throw new IllegalArgumentException("postingDay must be 1-31, got " + postingDay);
        }
        Amount amortisable = base.subtract(residual);
        if (amortisable.isZero()) {
            // Not a schedule of zeroes: a schedule of zeroes is a year of entries that say
            // nothing, and every one of them would have to be read before being ignored.
            throw new IllegalArgumentException("there is nothing to amortise: the base amount "
                    + base.toExactString() + " and the residual " + residual.toExactString()
                    + " are the same");
        }

        BigDecimal count = BigDecimal.valueOf((long) months);
        // DOWN, towards zero, so that the remainder carries the sign of the schedule and the
        // instalments never overshoot the amount being amortised.
        BigDecimal instalment = amortisable.value().divide(count, roundingDigits, RoundingMode.DOWN);
        BigDecimal remainder = amortisable.value().subtract(instalment.multiply(count));

        List<Line> built = new ArrayList<Line>();
        Amount running = Amount.ZERO;
        for (int index = 0; index < months; index++) {
            BigDecimal value = instalment;
            boolean takesRemainder = remainderTo == Remainder.START
                    ? index == 0
                    : index == months - 1;
            if (takesRemainder) {
                value = value.add(remainder);
            }
            YearMonth month = startMonth.plusMonths(index);
            LocalDate on = month.atDay(Math.min(postingDay, month.lengthOfMonth()));
            Amount amount = Amount.of(value);
            running = running.add(amount);
            built.add(new Line(index + 1, on, amount, base.subtract(running)));
        }

        if (!running.isEqualTo(amortisable)) {
            // Unreachable while the arithmetic above is exact, and that is the point: if someone
            // later replaces the remainder handling with something that drifts, it fails here
            // rather than in a ledger a quarter from now.
            throw new IllegalStateException("the schedule sums to " + running.toExactString()
                    + " but must sum to " + amortisable.toExactString()
                    + ". Amortisation must not lose or invent an amount.");
        }
        return new AmortizationSchedule(base, residual, months, roundingDigits, remainderTo, built);
    }

    public Amount base() {
        return base;
    }

    public Amount residual() {
        return residual;
    }

    public int months() {
        return months;
    }

    public int roundingDigits() {
        return roundingDigits;
    }

    public Remainder remainderTo() {
        return remainderTo;
    }

    public List<Line> lines() {
        return lines;
    }

    /** Base minus residual: what the lines add up to, exactly. */
    public Amount total() {
        Amount total = Amount.ZERO;
        for (Line line : lines) {
            total = total.add(line.amount());
        }
        return total;
    }

    /** One instalment. */
    public static final class Line implements Serializable {

        private static final long serialVersionUID = 1L;

        private final int number;
        private final LocalDate businessDate;
        private final Amount amount;
        private final Amount carryingAmount;

        Line(int number, LocalDate businessDate, Amount amount, Amount carryingAmount) {
            this.number = number;
            this.businessDate = businessDate;
            this.amount = amount;
            this.carryingAmount = carryingAmount;
        }

        /** 1-based, so the fourth line is line 4 in the preview a person signs off. */
        public int number() {
            return number;
        }

        /**
         * The business day this instalment falls on. Which day decides which period reports it,
         * so it is a date and not a wall-clock timestamp (ADR 0002).
         */
        public LocalDate businessDate() {
            return businessDate;
        }

        public Amount amount() {
            return amount;
        }

        /** What is left after this line. Ends at exactly the residual. */
        public Amount carryingAmount() {
            return carryingAmount;
        }

        @Override
        public String toString() {
            return number + " " + businessDate + " " + amount.toExactString();
        }
    }
}
