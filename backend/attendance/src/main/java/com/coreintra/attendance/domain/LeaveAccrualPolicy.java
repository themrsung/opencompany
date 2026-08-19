package com.coreintra.attendance.domain;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * How leave is earned — as configuration, not as code.
 *
 * <h2>Why this is data</h2>
 *
 * <p>Korean 연차 is set by statute, and statute changes. A client may also be
 * more generous than the minimum, may run a different scheme for contractors,
 * or may operate a subsidiary under another jurisdiction entirely. Encoding the
 * current rules in Java would mean a release every time any of that moved, and
 * would make the more-generous case impossible to express.
 *
 * <p>So the rules are rows: monthly accrual, annual grant, tenure increments,
 * carry-over, expiry, and the minimum bookable unit. A Korean 연차 policy is
 * seeded as an editable default; the statutory reference lives in a comment on
 * the seed, never in a code path.
 *
 * <p>Computation here is a pure function of policy plus tenure, so a client can
 * see what a change would do before saving it.
 */
public final class LeaveAccrualPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Extra days once an employee passes a tenure threshold. */
    public static final class TenureIncrement implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int afterCompletedYears;
        private final BigDecimal additionalDays;

        public TenureIncrement(int afterCompletedYears, BigDecimal additionalDays) {
            if (afterCompletedYears < 0) {
                throw new IllegalArgumentException("afterCompletedYears cannot be negative");
            }
            this.afterCompletedYears = afterCompletedYears;
            this.additionalDays = additionalDays;
        }

        public int afterCompletedYears() {
            return afterCompletedYears;
        }

        public BigDecimal additionalDays() {
            return additionalDays;
        }
    }

    private final String id;
    private final String nameKo;
    private final String nameEn;
    private final BigDecimal monthlyAccrualDays;
    private final BigDecimal annualGrantDays;
    private final int annualGrantAfterCompletedYears;
    private final List<TenureIncrement> tenureIncrements;
    private final BigDecimal maximumDays;
    private final BigDecimal carryOverLimitDays;
    private final int carryOverExpiryMonths;
    private final BigDecimal minimumBookableUnitDays;

    private LeaveAccrualPolicy(Builder builder) {
        this.id = builder.id;
        this.nameKo = builder.nameKo;
        this.nameEn = builder.nameEn;
        this.monthlyAccrualDays = builder.monthlyAccrualDays;
        this.annualGrantDays = builder.annualGrantDays;
        this.annualGrantAfterCompletedYears = builder.annualGrantAfterCompletedYears;
        List<TenureIncrement> sorted = new ArrayList<TenureIncrement>(builder.tenureIncrements);
        Collections.sort(sorted, new Comparator<TenureIncrement>() {
            @Override
            public int compare(TenureIncrement left, TenureIncrement right) {
                return Integer.compare(left.afterCompletedYears(), right.afterCompletedYears());
            }
        });
        this.tenureIncrements = Immutables.copyOf(sorted);
        this.maximumDays = builder.maximumDays;
        this.carryOverLimitDays = builder.carryOverLimitDays;
        this.carryOverExpiryMonths = builder.carryOverExpiryMonths;
        this.minimumBookableUnitDays = builder.minimumBookableUnitDays;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Entitlement for an employee with this hire date, as of a date.
     *
     * <p>Below the annual-grant tenure threshold, entitlement accrues monthly
     * per completed month of service. At or above it, the annual grant applies
     * plus any tenure increments reached, capped by {@link #maximumDays()}.
     *
     * <p>Pure: same inputs, same answer, no clock read.
     */
    public BigDecimal entitlementFor(LocalDate hiredOn, LocalDate asOf) {
        if (hiredOn == null || asOf.isBefore(hiredOn)) {
            return BigDecimal.ZERO;
        }
        long completedYears = ChronoUnit.YEARS.between(hiredOn, asOf);

        BigDecimal entitlement;
        if (completedYears < annualGrantAfterCompletedYears) {
            long completedMonths = ChronoUnit.MONTHS.between(hiredOn, asOf);
            entitlement = monthlyAccrualDays.multiply(BigDecimal.valueOf(completedMonths));
        } else {
            entitlement = annualGrantDays;
            for (TenureIncrement increment : tenureIncrements) {
                if (completedYears >= increment.afterCompletedYears()) {
                    entitlement = entitlement.add(increment.additionalDays());
                }
            }
        }
        if (maximumDays != null && entitlement.compareTo(maximumDays) > 0) {
            entitlement = maximumDays;
        }
        return entitlement;
    }

    /** How much of an unused balance carries into the next period. */
    public BigDecimal carryOverFrom(BigDecimal unusedDays) {
        if (unusedDays.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (carryOverLimitDays == null) {
            return unusedDays;
        }
        return unusedDays.min(carryOverLimitDays);
    }

    /** When carried-over days lapse, or null if they do not. */
    public LocalDate carryOverExpiryFrom(LocalDate carriedOn) {
        return carryOverExpiryMonths <= 0 ? null : carriedOn.plusMonths(carryOverExpiryMonths);
    }

    /**
     * Rounds a request up to the policy's minimum bookable unit.
     *
     * <p>Up, never down: rounding down would let a request book less leave than
     * it actually takes, and the difference accumulates in the employee's favour
     * in a way no policy intended.
     */
    public BigDecimal roundToBookableUnit(BigDecimal requestedDays) {
        if (minimumBookableUnitDays == null || minimumBookableUnitDays.signum() <= 0) {
            return requestedDays;
        }
        BigDecimal units = requestedDays.divide(minimumBookableUnitDays, 0, RoundingMode.CEILING);
        return units.multiply(minimumBookableUnitDays);
    }

    /** True when a request is a whole multiple of the bookable unit. */
    public boolean isBookable(BigDecimal requestedDays) {
        if (minimumBookableUnitDays == null || minimumBookableUnitDays.signum() <= 0) {
            return requestedDays.signum() > 0;
        }
        return requestedDays.signum() > 0
                && requestedDays.remainder(minimumBookableUnitDays).signum() == 0;
    }

    public String id() {
        return id;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public BigDecimal monthlyAccrualDays() {
        return monthlyAccrualDays;
    }

    public BigDecimal annualGrantDays() {
        return annualGrantDays;
    }

    public int annualGrantAfterCompletedYears() {
        return annualGrantAfterCompletedYears;
    }

    public List<TenureIncrement> tenureIncrements() {
        return tenureIncrements;
    }

    public BigDecimal maximumDays() {
        return maximumDays;
    }

    public BigDecimal carryOverLimitDays() {
        return carryOverLimitDays;
    }

    public int carryOverExpiryMonths() {
        return carryOverExpiryMonths;
    }

    /** 1 = whole days, 0.5 = half-days, 0.25 = quarter-days. */
    public BigDecimal minimumBookableUnitDays() {
        return minimumBookableUnitDays;
    }

    public static final class Builder {
        private final String id;
        private String nameKo;
        private String nameEn;
        private BigDecimal monthlyAccrualDays = BigDecimal.ZERO;
        private BigDecimal annualGrantDays = BigDecimal.ZERO;
        private int annualGrantAfterCompletedYears = 1;
        private final List<TenureIncrement> tenureIncrements = new ArrayList<TenureIncrement>();
        private BigDecimal maximumDays;
        private BigDecimal carryOverLimitDays;
        private int carryOverExpiryMonths;
        private BigDecimal minimumBookableUnitDays = BigDecimal.ONE;

        Builder(String id) {
            this.id = id;
        }

        public Builder name(String ko, String en) {
            this.nameKo = ko;
            this.nameEn = en;
            return this;
        }

        public Builder monthlyAccrualDays(BigDecimal value) {
            this.monthlyAccrualDays = value;
            return this;
        }

        public Builder annualGrantDays(BigDecimal value) {
            this.annualGrantDays = value;
            return this;
        }

        public Builder annualGrantAfterCompletedYears(int value) {
            this.annualGrantAfterCompletedYears = value;
            return this;
        }

        public Builder tenureIncrement(int afterCompletedYears, BigDecimal additionalDays) {
            this.tenureIncrements.add(new TenureIncrement(afterCompletedYears, additionalDays));
            return this;
        }

        public Builder maximumDays(BigDecimal value) {
            this.maximumDays = value;
            return this;
        }

        public Builder carryOverLimitDays(BigDecimal value) {
            this.carryOverLimitDays = value;
            return this;
        }

        public Builder carryOverExpiryMonths(int value) {
            this.carryOverExpiryMonths = value;
            return this;
        }

        public Builder minimumBookableUnitDays(BigDecimal value) {
            this.minimumBookableUnitDays = value;
            return this;
        }

        public LeaveAccrualPolicy build() {
            return new LeaveAccrualPolicy(this);
        }
    }

    /**
     * The seeded Korean 연차 default. Editable, and meant to be edited.
     *
     * <p>Shaped after 근로기준법 제60조 as commonly applied: one day per completed
     * month in the first year, fifteen days from the second, and one additional
     * day every two years thereafter, capped at twenty-five.
     *
     * <p>This is a <b>seed value, not a rule the code enforces</b>. Nothing else
     * in the system knows these numbers, the statutory reference lives here in
     * prose rather than in a code path, and a client who edits or deletes this
     * policy breaks nothing. If the statute changes, this seed is updated and
     * existing installations are unaffected — which is the entire reason accrual
     * is configuration.
     */
    public static LeaveAccrualPolicy koreanAnnualLeaveSeed(String id) {
        return builder(id)
                .name("연차유급휴가 (기본)", "Annual paid leave (default)")
                .monthlyAccrualDays(BigDecimal.ONE)
                .annualGrantDays(new BigDecimal("15"))
                .annualGrantAfterCompletedYears(1)
                .tenureIncrement(3, BigDecimal.ONE)
                .tenureIncrement(5, new BigDecimal("2"))
                .tenureIncrement(7, new BigDecimal("3"))
                .tenureIncrement(9, new BigDecimal("4"))
                .maximumDays(new BigDecimal("25"))
                .carryOverLimitDays(null)
                .carryOverExpiryMonths(12)
                .minimumBookableUnitDays(new BigDecimal("0.5"))
                .build();
    }
}
