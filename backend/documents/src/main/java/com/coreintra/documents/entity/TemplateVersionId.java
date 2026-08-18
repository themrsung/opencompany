package com.coreintra.documents.entity;

import java.io.Serializable;

/** Composite key: a template version is identified by its template and its number. */
public class TemplateVersionId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String templateId;

    private Integer versionNo;

    public TemplateVersionId() {
    }

    public TemplateVersionId(String templateId, Integer versionNo) {
        this.templateId = templateId;
        this.versionNo = versionNo;
    }

    public String templateId() {
        return templateId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TemplateVersionId)) {
            return false;
        }
        TemplateVersionId other = (TemplateVersionId) obj;
        if (templateId == null ? other.templateId != null : !templateId.equals(other.templateId)) {
            return false;
        }
        return versionNo == null ? other.versionNo == null : versionNo.equals(other.versionNo);
    }

    @Override
    public int hashCode() {
        int result = templateId == null ? 0 : templateId.hashCode();
        return result * 31 + (versionNo == null ? 0 : versionNo.hashCode());
    }

    @Override
    public String toString() {
        return templateId + " v" + versionNo;
    }
}
