package com.coreintra.approval.entity;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * One 조 of a 취업규칙 version.
 *
 * <p>Stored per section rather than as one body of text, because §4 asks for a
 * section-level diff against the prior version. A single blob would make
 * "제12조 changed and nothing else did" something a diff library infers from
 * prose, and it would infer it differently after every reformatting.
 *
 * <p>{@code sectionNumber} is text, not a number: 제12조의2 is a real section
 * number and sorts nowhere sensible. {@code sortOrder} carries the printed
 * order separately for that reason.
 */
@Entity
@Table(name = "employment_rules_section")
@IdClass(EmploymentRulesSectionEntity.Key.class)
public class EmploymentRulesSectionEntity {

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private String rulesId;
        private String sectionNumber;

        public Key() {
        }

        public Key(String rulesId, String sectionNumber) {
            this.rulesId = rulesId;
            this.sectionNumber = sectionNumber;
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
            return rulesId.equals(other.rulesId) && sectionNumber.equals(other.sectionNumber);
        }

        @Override
        public int hashCode() {
            return rulesId.hashCode() * 31 + sectionNumber.hashCode();
        }
    }

    @Id
    @Column(name = "rules_id", length = 36)
    private String rulesId;

    @Id
    @Column(name = "section_number", length = 40)
    private String sectionNumber;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "heading_ko", nullable = false, length = 500)
    private String headingKo;

    @Column(name = "heading_en", length = 500)
    private String headingEn;

    @Column(name = "body_ko", nullable = false)
    private String bodyKo;

    @Column(name = "body_en")
    private String bodyEn;

    protected EmploymentRulesSectionEntity() {
    }

    public EmploymentRulesSectionEntity(String rulesId, String sectionNumber, int sortOrder,
            String headingKo, String headingEn, String bodyKo, String bodyEn) {
        this.rulesId = rulesId;
        this.sectionNumber = sectionNumber;
        this.sortOrder = sortOrder;
        this.headingKo = headingKo;
        this.headingEn = headingEn;
        this.bodyKo = bodyKo;
        this.bodyEn = bodyEn;
    }

    public String rulesId() {
        return rulesId;
    }

    public String sectionNumber() {
        return sectionNumber;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public String headingKo() {
        return headingKo;
    }

    public String headingEn() {
        return headingEn;
    }

    public String bodyKo() {
        return bodyKo;
    }

    public String bodyEn() {
        return bodyEn;
    }
}
