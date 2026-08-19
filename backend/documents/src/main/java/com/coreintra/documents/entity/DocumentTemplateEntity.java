package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A template family: the thing a client picks from a list. It carries no bytes.
 *
 * <p>The bytes and the field manifest live on {@link DocumentTemplateVersionEntity},
 * because a submitted document promises to render identically forever and can only
 * keep that promise if the version it names never moves (brief 6.8).
 *
 * <p>{@code currentVersionNo} is NULL until a version is published. A template with
 * no published version cannot be drafted from, which is the difference between
 * "being written" and "available".
 */
@Entity
@Table(name = "document_template")
public class DocumentTemplateEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Column(name = "current_version_no")
    private Integer currentVersionNo;

    /** Seeded rows can be restored to factory state; client rows cannot. */
    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by_account_id", length = 36)
    private String createdByAccountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    protected DocumentTemplateEntity() {
    }

    public DocumentTemplateEntity(String id, String companyId, String code, String documentType,
            String nameKo, String createdByAccountId) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.documentType = documentType;
        this.nameKo = nameKo;
        this.createdByAccountId = createdByAccountId;
        this.createdAt = OffsetDateTime.now();
    }

    /** Moves the pointer a new draft will be created from. Old documents keep their own. */
    public void publishVersion(int versionNo) {
        this.currentVersionNo = Integer.valueOf(versionNo);
    }

    public void retire(OffsetDateTime at) {
        this.retiredAt = at;
        this.active = false;
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

    public String documentType() {
        return documentType;
    }

    public String nameKo() {
        return nameKo;
    }

    public void setNameKo(String value) {
        this.nameKo = value;
    }

    public String nameEn() {
        return nameEn;
    }

    public void setNameEn(String value) {
        this.nameEn = value;
    }

    public Integer currentVersionNo() {
        return currentVersionNo;
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

    public void setActive(boolean value) {
        this.active = value;
    }

    public String createdByAccountId() {
        return createdByAccountId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }
}
