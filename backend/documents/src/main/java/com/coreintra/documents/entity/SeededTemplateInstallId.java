package com.coreintra.documents.entity;

import java.io.Serializable;

/** Composite key: one factory install record per company per template code. */
public class SeededTemplateInstallId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String companyId;

    private String code;

    public SeededTemplateInstallId() {
    }

    public SeededTemplateInstallId(String companyId, String code) {
        this.companyId = companyId;
        this.code = code;
    }

    public String companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SeededTemplateInstallId)) {
            return false;
        }
        SeededTemplateInstallId other = (SeededTemplateInstallId) obj;
        return equal(companyId, other.companyId) && equal(code, other.code);
    }

    private static boolean equal(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    @Override
    public int hashCode() {
        int result = companyId == null ? 0 : companyId.hashCode();
        return 31 * result + (code == null ? 0 : code.hashCode());
    }
}
