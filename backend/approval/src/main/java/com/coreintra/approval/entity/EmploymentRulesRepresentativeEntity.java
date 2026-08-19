package com.coreintra.approval.entity;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * A 대표 who signed one 취업규칙 version.
 *
 * <p>Snapshotted, and distinct by primary key. Under 공동대표 the quorum is the
 * whole point: "two representatives approved this" has to stay answerable after
 * both have left the company, and one person approving twice must not look like
 * two.
 */
@Entity
@Table(name = "employment_rules_representative")
@IdClass(EmploymentRulesRepresentativeEntity.Key.class)
public class EmploymentRulesRepresentativeEntity {

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private String rulesId;
        private String accountId;

        public Key() {
        }

        public Key(String rulesId, String accountId) {
            this.rulesId = rulesId;
            this.accountId = accountId;
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
            return rulesId.equals(other.rulesId) && accountId.equals(other.accountId);
        }

        @Override
        public int hashCode() {
            return rulesId.hashCode() * 31 + accountId.hashCode();
        }
    }

    @Id
    @Column(name = "rules_id", length = 36)
    private String rulesId;

    @Id
    @Column(name = "account_id", length = 36)
    private String accountId;

    /** The order they were recorded in, so the 결재란 reads the same every time. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected EmploymentRulesRepresentativeEntity() {
    }

    public EmploymentRulesRepresentativeEntity(String rulesId, String accountId, int sortOrder) {
        this.rulesId = rulesId;
        this.accountId = accountId;
        this.sortOrder = sortOrder;
    }

    public String rulesId() {
        return rulesId;
    }

    public String accountId() {
        return accountId;
    }

    public int sortOrder() {
        return sortOrder;
    }
}
