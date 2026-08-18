package com.coreintra.documents.entity;

import java.io.Serializable;

/** Composite key: one body per template version per locale. */
public class TemplateBodyId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String templateId;

    private Integer versionNo;

    private String locale;

    public TemplateBodyId() {
    }

    public TemplateBodyId(String templateId, Integer versionNo, String locale) {
        this.templateId = templateId;
        this.versionNo = versionNo;
        this.locale = locale;
    }

    public String templateId() {
        return templateId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String locale() {
        return locale;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TemplateBodyId)) {
            return false;
        }
        TemplateBodyId other = (TemplateBodyId) obj;
        if (templateId == null ? other.templateId != null : !templateId.equals(other.templateId)) {
            return false;
        }
        if (versionNo == null ? other.versionNo != null : !versionNo.equals(other.versionNo)) {
            return false;
        }
        return locale == null ? other.locale == null : locale.equals(other.locale);
    }

    @Override
    public int hashCode() {
        int result = templateId == null ? 0 : templateId.hashCode();
        result = result * 31 + (versionNo == null ? 0 : versionNo.hashCode());
        return result * 31 + (locale == null ? 0 : locale.hashCode());
    }

    @Override
    public String toString() {
        return templateId + " v" + versionNo + " (" + locale + ")";
    }
}
