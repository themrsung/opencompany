package com.coreintra.documents.entity;

import java.io.Serializable;

/** Composite key: a version is identified by its document and its number. */
public class DocumentVersionId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String documentId;

    private Integer versionNo;

    public DocumentVersionId() {
    }

    public DocumentVersionId(String documentId, Integer versionNo) {
        this.documentId = documentId;
        this.versionNo = versionNo;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DocumentVersionId)) {
            return false;
        }
        DocumentVersionId other = (DocumentVersionId) obj;
        if (documentId == null ? other.documentId != null : !documentId.equals(other.documentId)) {
            return false;
        }
        return versionNo == null ? other.versionNo == null : versionNo.equals(other.versionNo);
    }

    @Override
    public int hashCode() {
        int result = documentId == null ? 0 : documentId.hashCode();
        return result * 31 + (versionNo == null ? 0 : versionNo.hashCode());
    }

    @Override
    public String toString() {
        return documentId + " v" + versionNo;
    }
}
