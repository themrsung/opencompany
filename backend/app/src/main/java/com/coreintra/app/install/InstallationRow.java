package com.coreintra.app.install;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The one row that says this installation has been opened.
 *
 * <p>It is a lock before it is a record. {@code POST /api/v1/install} is
 * unauthenticated of necessity — on an empty box there is nobody to be — so
 * something has to make "empty" unrepeatable. Counting accounts is the readable
 * rule; this row's primary key is what survives two requests arriving at once,
 * because the second one blocks on the insert and then fails on the duplicate
 * key with its whole transaction rolled back.
 *
 * <p>{@code V15__installation.sql} refuses UPDATE and DELETE on it by trigger:
 * being able to remove this row would reopen the installer on a system in use.
 */
@Entity
@Table(name = "installation")
public class InstallationRow {

    /** The only id this table may hold; the database checks it too. */
    public static final String ID = "installation";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** UTC, what the machine observed. An installation has no business date before it exists. */
    @Column(name = "installed_at", nullable = false)
    private OffsetDateTime installedAt;

    @Column(name = "master_account_id", nullable = false, length = 36)
    private String masterAccountId;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    protected InstallationRow() {
    }

    public InstallationRow(String masterAccountId, String companyId) {
        this.id = ID;
        this.masterAccountId = masterAccountId;
        this.companyId = companyId;
        this.installedAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public OffsetDateTime installedAt() {
        return installedAt;
    }

    public String masterAccountId() {
        return masterAccountId;
    }

    public String companyId() {
        return companyId;
    }
}
