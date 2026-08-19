package com.coreintra.documents.entity;

import java.io.Serializable;

/** Composite key: one row per field per version of a document. */
public class DocumentFieldValueId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String documentId;

    private Integer versionNo;

    private String fieldId;

    public DocumentFieldValueId() {
    }

    public DocumentFieldValueId(String documentId, Integer versionNo, String fieldId) {
        this.documentId = documentId;
        this.versionNo = versionNo;
        this.fieldId = fieldId;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String fieldId() {
        return fieldId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DocumentFieldValueId)) {
            return false;
        }
        DocumentFieldValueId other = (DocumentFieldValueId) obj;
        if (documentId == null ? other.documentId != null : !documentId.equals(other.documentId)) {
            return false;
        }
        if (versionNo == null ? other.versionNo != null : !versionNo.equals(other.versionNo)) {
            return false;
        }
        return fieldId == null ? other.fieldId == null : fieldId.equals(other.fieldId);
    }

    @Override
    public int hashCode() {
        int result = documentId == null ? 0 : documentId.hashCode();
        result = result * 31 + (versionNo == null ? 0 : versionNo.hashCode());
        return result * 31 + (fieldId == null ? 0 : fieldId.hashCode());
    }

    @Override
    public String toString() {
        return documentId + " v" + versionNo + " #" + fieldId;
    }
}
