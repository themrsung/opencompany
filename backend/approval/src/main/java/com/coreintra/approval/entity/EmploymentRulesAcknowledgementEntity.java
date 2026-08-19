package com.coreintra.approval.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * A receipt that one employee has read one 취업규칙 version.
 *
 * <p>Per employee <em>per version</em>: having read the 2024 rules says nothing
 * about having read the 2026 ones, and the receipt an employer has to produce is
 * always for a specific version.
 *
 * <p>The composite primary key is what makes acknowledging twice a no-op rather
 * than a second receipt bearing a different date — the second date would be the
 * one that ends up being quoted, and it would be the wrong one.
 */
@Entity
@Table(name = "employment_rules_acknowledgement")
@IdClass(EmploymentRulesAcknowledgementEntity.Key.class)
public class EmploymentRulesAcknowledgementEntity {

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private String rulesId;
        private String employeeId;

        public Key() {
        }

        public Key(String rulesId, String employeeId) {
            this.rulesId = rulesId;
            this.employeeId = employeeId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Key other = (Key) obj;
            return rulesId.equals(other.rulesId) && employeeId.equals(other.employeeId);
        }

        @Override
        public int hashCode() {
            return rulesId.hashCode() * 31 + employeeId.hashCode();
        }
    }

    @Id
    @Column(name = "rules_id", length = 36)
    private String rulesId;

    @Id
    @Column(name = "employee_id", length = 36)
    private String employeeId;

    /** Business date: the day the organisation says they read it. */
    @Column(name = "acknowledged_on", nullable = false)
    private LocalDate acknowledgedOn;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected EmploymentRulesAcknowledgementEntity() {
    }

    public EmploymentRulesAcknowledgementEntity(String rulesId, String employeeId,
            LocalDate acknowledgedOn) {
        this.rulesId = rulesId;
        this.employeeId = employeeId;
        this.acknowledgedOn = acknowledgedOn;
        this.createdAt = OffsetDateTime.now();
    }

    public String rulesId() {
        return rulesId;
    }

    public String employeeId() {
        return employeeId;
    }

    public LocalDate acknowledgedOn() {
        return acknowledgedOn;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
