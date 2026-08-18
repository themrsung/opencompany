package com.coreintra.runtime.support;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import java.io.Serializable;

/**
 * One representative who approved an issuance, snapshotted.
 *
 * <p>Under a joint quorum the individuals have to be named, because two
 * approvals from one person is one approval. The name is stored beside the id
 * for the same reason it is on an approval action: read back in three years,
 * an id resolves to whoever holds that account now.
 */
@Embeddable
public class TemporaryMasterApprover implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "representative_account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "representative_name", nullable = false, length = 200)
    private String name;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected TemporaryMasterApprover() {
    }

    public TemporaryMasterApprover(String accountId, String name) {
        this.accountId = accountId;
        this.name = name;
    }

    public String accountId() {
        return accountId;
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TemporaryMasterApprover)) {
            return false;
        }
        TemporaryMasterApprover other = (TemporaryMasterApprover) obj;
        return accountId == null ? other.accountId == null : accountId.equals(other.accountId);
    }

    @Override
    public int hashCode() {
        return accountId == null ? 0 : accountId.hashCode();
    }

    @Override
    public String toString() {
        return name + " (" + accountId + ")";
    }
}
