package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The mutable head of a document: what it is called and where its versions are.
 *
 * <p>Every byte it has ever held is in {@link DocumentVersionEntity}. This row moves;
 * those do not.
 *
 * <p>{@code templateId} and {@code templateVersionNo} are pinned at creation and never
 * follow the template forward. A document approved against v3 renders against v3 for
 * the rest of its life, whatever v7 says (brief 6.8).
 */
@Entity
@Table(name = "document")
public class DocumentEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** NULL for the moment between INSERT and the first version being written. */
    @Column(name = "current_version_no")
    private Integer currentVersionNo;

    @Column(name = "template_id", length = 36)
    private String templateId;

    @Column(name = "template_version_no")
    private Integer templateVersionNo;

    @Column(name = "created_by_account_id", nullable = false, length = 36)
    private String createdByAccountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    protected DocumentEntity() {
    }

    public DocumentEntity(String id, String companyId, String documentType, String title,
            String templateId, Integer templateVersionNo, String createdByAccountId) {
        this.id = id;
        this.companyId = companyId;
        this.documentType = documentType;
        this.title = title;
        this.templateId = templateId;
        this.templateVersionNo = templateVersionNo;
        this.createdByAccountId = createdByAccountId;
        this.createdAt = OffsetDateTime.now();
    }

    /** Called when a version lands. The head is a pointer; the versions are the record. */
    public void headIsNow(int versionNo) {
        this.currentVersionNo = Integer.valueOf(versionNo);
    }

    public void retire(OffsetDateTime at) {
        this.retiredAt = at;
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

    public String title() {
        return title;
    }

    public void setTitle(String value) {
        this.title = value;
    }

    public Integer currentVersionNo() {
        return currentVersionNo;
    }

    public String templateId() {
        return templateId;
    }

    public Integer templateVersionNo() {
        return templateVersionNo;
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

    public boolean isRetired() {
        return retiredAt != null;
    }
}
