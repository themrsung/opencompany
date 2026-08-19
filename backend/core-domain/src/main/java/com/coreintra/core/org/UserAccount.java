package com.coreintra.core.org;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * An account that can sign in or call the API.
 *
 * <p>An employee has zero or one account — plenty of staff never sign in.
 * A service account has no employee at all, which is why {@link #employeeId} is
 * nullable and why nothing may assume it is set.
 *
 * <h2>There is no password column</h2>
 *
 * <p>Deliberately. Credentials are a TOTP secret and hashed recovery codes,
 * stored in the auth module's own tables. Nothing here can be used to
 * authenticate, so a leak of this table alone signs nobody in.
 *
 * <h2>Master is a flag, not a magic id</h2>
 *
 * <p>There may be several masters and the system refuses to remove the last
 * active one. Master status does not bypass the permission evaluator: it comes
 * with broad grants that are visible in the explainer, so revoking one
 * genuinely revokes the ability.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    public enum AccountKind {
        /** A person. Has an employee, signs in with TOTP. */
        USER,
        /** A client module, MCP client or scheduled job. Authenticates with a scoped API key. */
        SERVICE_ACCOUNT,
        /**
         * A vendor support session. Time-boxed, capabilities ticked one at a
         * time, every request logged including reads.
         */
        TEMPORARY_MASTER
    }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private AccountKind kind;

    /** Null for a service account or a temporary master. */
    @Column(name = "employee_id", length = 36)
    private String employeeId;

    @Column(name = "master", nullable = false)
    private boolean master;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Per-user language preference; falls back to the installation default. */
    @Column(name = "locale", length = 16)
    private String locale;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    protected UserAccount() {
    }

    public UserAccount(String id, String username, String displayName, AccountKind kind) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.kind = kind;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String value) {
        this.displayName = value;
    }

    public AccountKind kind() {
        return kind;
    }

    public String employeeId() {
        return employeeId;
    }

    public void linkToEmployee(String value) {
        this.employeeId = value;
    }

    public boolean isMaster() {
        return master;
    }

    /**
     * Grants or removes master status.
     *
     * <p>The "never fewer than one active master" rule is not enforced here: a
     * single entity cannot see the others. It is enforced by the service that
     * owns the invariant, which counts first and refuses with a clear message.
     */
    public void setMaster(boolean value) {
        this.master = value;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    public String locale() {
        return locale;
    }

    public void setLocale(String value) {
        this.locale = value;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime lastSeenAt() {
        return lastSeenAt;
    }

    public void touchLastSeen() {
        this.lastSeenAt = OffsetDateTime.now();
    }
}
