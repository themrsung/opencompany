package com.coreintra.attendance.entity;

import java.math.BigDecimal;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One rung of a policy's tenure ladder: extra days after N completed years.
 *
 * <p>A separate table rather than columns on the policy because the ladder has
 * no fixed length. The Korean 연차 default has four rungs today and a client is
 * free to write ten; encoding "after 3 / after 5 / after 7 years" as columns
 * would make the eleventh rung a migration.
 */
@Entity
@Table(name = "leave_tenure_increment")
public class LeaveTenureIncrement {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "policy_id", nullable = false, length = 36)
    private String policyId;

    @Column(name = "after_completed_years", nullable = false)
    private int afterCompletedYears;

    @Column(name = "additional_days", nullable = false, precision = 38, scale = 10)
    private BigDecimal additionalDays;

    protected LeaveTenureIncrement() {
    }

    public LeaveTenureIncrement(String id, String policyId, int afterCompletedYears,
            BigDecimal additionalDays) {
        this.id = id;
        this.policyId = policyId;
        this.afterCompletedYears = afterCompletedYears;
        this.additionalDays = additionalDays;
    }

    public String id() {
        return id;
    }

    public String policyId() {
        return policyId;
    }

    public int afterCompletedYears() {
        return afterCompletedYears;
    }

    public BigDecimal additionalDays() {
        return additionalDays;
    }
}
