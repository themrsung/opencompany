package com.coreintra.core.org;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 직무 — what the person actually does: 회계, 인사, 개발, 영업.
 *
 * <p>Independent of {@link Rank} and many-to-many with employees. A 과장 in
 * 회계 and a 과장 in 영업 sit at the same rung and do entirely different work,
 * and permissions follow the work at least as often as the rung — which is why
 * a grant can attach to either.
 */
@Entity
@Table(name = "job_function")
public class JobFunction {

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

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected JobFunction() {
    }

    public JobFunction(String id, String companyId, String code, String labelKo) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.labelKo = labelKo;
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

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }
}
