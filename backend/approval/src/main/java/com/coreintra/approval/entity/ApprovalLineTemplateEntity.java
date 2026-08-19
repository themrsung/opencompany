package com.coreintra.approval.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A 결재선 template header: which document type, in which org unit.
 *
 * <p>The steps are a separate table rather than a collection mapping, because
 * the threshold rules are steps too — a rule is "these extra signatures above
 * this amount" — and flattening them into one row list is what lets a template
 * be read with a single indexed query.
 *
 * <p>A null {@code orgUnitId} is the company-wide default for the type. It is
 * not a missing value: it is the row every unit falls back to, and the reason
 * a company that has configured nothing still gets a line.
 */
@Entity
@Table(name = "approval_line_template")
public class ApprovalLineTemplateEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    /** Null means the company-wide default for this document type. */
    @Column(name = "org_unit_id", length = 36)
    private String orgUnitId;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    /**
     * Retirement, not deletion. A template that routed documents last year has
     * to stay readable, because their trails name it.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ApprovalLineTemplateEntity() {
    }

    public ApprovalLineTemplateEntity(String id, String companyId, String documentType,
            String orgUnitId, String nameKo) {
        this.id = id;
        this.companyId = companyId;
        this.documentType = documentType;
        this.orgUnitId = orgUnitId;
        this.nameKo = nameKo;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String documentType() {
        return documentType;
    }

    public String orgUnitId() {
        return orgUnitId;
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

    public boolean isActive() {
        return active;
    }

    public void retire() {
        this.active = false;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
