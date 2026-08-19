package com.coreintra.core.org;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 직급 — a rung on the seniority ladder.
 *
 * <p>Fully client-definable: the labels, the ordering, and how many rungs there
 * are. 사원·대리·과장·차장·부장·이사·대표 ships as seed data that a client can
 * rename, reorder, delete or replace entirely. Nothing in the code compares a
 * rank by name, and no Korean title is special-cased.
 *
 * <p>{@link #seniority} is the only thing the system reads. Approval rules that
 * say "the 부장 of the drafter's unit" resolve through the rank the client
 * marked, not through a hardcoded string.
 */
@Entity
@Table(name = "rank")
public class Rank {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "label_ko", nullable = false, length = 100)
    private String labelKo;

    @Column(name = "label_en", length = 100)
    private String labelEn;

    /**
     * Higher is more senior. Client-defined and need not be contiguous — gaps
     * leave room to insert a rung later without renumbering everyone.
     */
    @Column(name = "seniority", nullable = false)
    private int seniority;

    /**
     * Whether holders of this rank are 대표이사 for representation purposes.
     *
     * <p>A flag rather than a code match, because "the 대표" is a role a client
     * may name anything and may have several of.
     */
    @Column(name = "representative", nullable = false)
    private boolean representative;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Rank() {
    }

    public Rank(String id, String companyId, String code, String labelKo, int seniority) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.labelKo = labelKo;
        this.seniority = seniority;
    }

    public boolean isMoreSeniorThan(Rank other) {
        return seniority > other.seniority;
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

    public String labelKo() {
        return labelKo;
    }

    public String labelEn() {
        return labelEn;
    }

    public void relabel(String labelKo, String labelEn) {
        this.labelKo = labelKo;
        this.labelEn = labelEn;
    }

    public int seniority() {
        return seniority;
    }

    public void setSeniority(int value) {
        this.seniority = value;
    }

    public boolean isRepresentative() {
        return representative;
    }

    public void setRepresentative(boolean value) {
        this.representative = value;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }
}
