package com.coreintra.attendance.entity;

import java.math.BigDecimal;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * An accrual policy row: every number that decides an entitlement.
 *
 * <p>Accrual is configuration, never code. Statute changes, clients are
 * routinely more generous than the minimum, and an overseas subsidiary runs
 * another scheme entirely — so monthly accrual, the annual grant and the tenure
 * at which it starts, the carry-over limit and its expiry, and the smallest
 * bookable unit are all columns here. The Korean 연차 default is a seeded,
 * editable row (V6) whose statutory reference lives in the migration's comment
 * rather than in any code path.
 *
 * <p>Days are {@link BigDecimal} because half- and quarter-day units are
 * ordinary: a policy with a 0.5 unit and a balance held as a floating type would
 * eventually refuse a request for exactly the days somebody has.
 */
@Entity
@Table(name = "leave_policy")
public class LeavePolicy {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    /** Stable identifier the rest of the system refers to, e.g. {@code ANNUAL}. */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Column(name = "monthly_accrual_days", nullable = false, precision = 38, scale = 10)
    private BigDecimal monthlyAccrualDays = BigDecimal.ZERO;

    @Column(name = "annual_grant_days", nullable = false, precision = 38, scale = 10)
    private BigDecimal annualGrantDays = BigDecimal.ZERO;

    @Column(name = "annual_grant_after_years", nullable = false)
    private int annualGrantAfterYears = 1;

    /** Null = no ceiling. */
    @Column(name = "maximum_days", precision = 38, scale = 10)
    private BigDecimal maximumDays;

    /** Null = everything unused carries over. */
    @Column(name = "carry_over_limit_days", precision = 38, scale = 10)
    private BigDecimal carryOverLimitDays;

    @Column(name = "carry_over_expiry_months", nullable = false)
    private int carryOverExpiryMonths;

    /** 1 = whole days, 0.5 = half-days, 0.25 = quarter-days. */
    @Column(name = "minimum_bookable_unit_days", nullable = false, precision = 38, scale = 10)
    private BigDecimal minimumBookableUnitDays = BigDecimal.ONE;

    /** Seeded rows can be restored to factory state; client rows cannot. */
    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected LeavePolicy() {
    }

    public LeavePolicy(String id, String companyId, String code, String nameKo) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.nameKo = nameKo;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public void rename(String ko, String en) {
        this.nameKo = ko;
        this.nameEn = en;
    }

    public BigDecimal monthlyAccrualDays() {
        return monthlyAccrualDays;
    }

    public void setMonthlyAccrualDays(BigDecimal value) {
        this.monthlyAccrualDays = value;
    }

    public BigDecimal annualGrantDays() {
        return annualGrantDays;
    }

    public void setAnnualGrantDays(BigDecimal value) {
        this.annualGrantDays = value;
    }

    public int annualGrantAfterYears() {
        return annualGrantAfterYears;
    }

    public void setAnnualGrantAfterYears(int value) {
        this.annualGrantAfterYears = value;
    }

    public BigDecimal maximumDays() {
        return maximumDays;
    }

    public void setMaximumDays(BigDecimal value) {
        this.maximumDays = value;
    }

    public BigDecimal carryOverLimitDays() {
        return carryOverLimitDays;
    }

    public void setCarryOverLimitDays(BigDecimal value) {
        this.carryOverLimitDays = value;
    }

    public int carryOverExpiryMonths() {
        return carryOverExpiryMonths;
    }

    public void setCarryOverExpiryMonths(int value) {
        this.carryOverExpiryMonths = value;
    }

    public BigDecimal minimumBookableUnitDays() {
        return minimumBookableUnitDays;
    }

    public void setMinimumBookableUnitDays(BigDecimal value) {
        this.minimumBookableUnitDays = value;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean value) {
        this.builtIn = value;
    }

    public boolean isActive() {
        return active;
    }

    public void retire() {
        this.active = false;
    }
}
