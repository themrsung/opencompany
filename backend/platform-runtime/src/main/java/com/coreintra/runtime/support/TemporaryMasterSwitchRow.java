package com.coreintra.runtime.support;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * The kill switch (§8): one row, installation-wide.
 *
 * <p>It is a table rather than a setting because a client who has decided never
 * to accept vendor sessions again should not be able to be talked out of it by a
 * support engineer with a settings screen. The trigger in the migration makes
 * the decision one-way; this class simply has no method that re-enables.
 */
@Entity
@Table(name = "temporary_master_switch")
public class TemporaryMasterSwitchRow {

    /** The only permitted primary key. There is one installation. */
    public static final String INSTALLATION = "INSTALLATION";

    @Id
    @Column(name = "installation", length = 16)
    private String installation;

    @Column(name = "issuance_disabled", nullable = false)
    private boolean issuanceDisabled;

    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    @Column(name = "disabled_by_account_id", length = 36)
    private String disabledByAccountId;

    @Column(name = "disabled_reason", length = 400)
    private String disabledReason;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected TemporaryMasterSwitchRow() {
    }

    public String installation() {
        return installation;
    }

    public boolean issuanceDisabled() {
        return issuanceDisabled;
    }

    public OffsetDateTime disabledAt() {
        return disabledAt;
    }

    public String disabledByAccountId() {
        return disabledByAccountId;
    }

    public String disabledReason() {
        return disabledReason;
    }

    /**
     * Permanent for this installation.
     *
     * <p>There is no matching {@code enable}. Reinstating vendor sessions is a
     * reinstallation decision, made by whoever administers the box, not a
     * request the application can be persuaded to serve.
     */
    void disableIssuance(String byAccountId, String reason, OffsetDateTime at) {
        this.issuanceDisabled = true;
        this.disabledByAccountId = byAccountId;
        this.disabledReason = reason;
        this.disabledAt = at;
    }
}
