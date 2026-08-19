package com.coreintra.core.permission;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.Set;

/**
 * Who is asking.
 *
 * <p>One type for every caller — a signed-in person, a service account used by
 * a client module, an MCP token, a scheduled job. There is no separate
 * "internal" principal that skips checks, because a bypass path is a permission
 * system with a hole in it. A scheduled job runs as a named service account
 * with grants that can be inspected like anyone else's.
 */
public final class PermissionPrincipal implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What kind of caller this is. Affects auditing, never authorisation. */
    public enum Kind {
        /** A person signed in through the UI or the API. */
        USER,
        /** A key-authenticated non-person: client module, MCP client, job. */
        SERVICE_ACCOUNT,
        /**
         * A vendor support session. Its capabilities are ticked individually and
         * default to nothing; see the temporary-master feature. Flagged
         * separately so every read it performs can be logged.
         */
        TEMPORARY_MASTER
    }

    private final String accountId;
    private final String displayName;
    private final Kind kind;
    private final String employeeId;
    private final boolean master;
    private final Set<String> apiKeyScopes;

    private PermissionPrincipal(String accountId, String displayName, Kind kind, String employeeId,
            boolean master, Set<String> apiKeyScopes) {
        if (accountId == null) {
            throw new NullPointerException("accountId");
        }
        this.accountId = accountId;
        this.displayName = displayName;
        this.kind = kind == null ? Kind.USER : kind;
        this.employeeId = employeeId;
        this.master = master;
        this.apiKeyScopes = apiKeyScopes == null
                ? Immutables.<String>setOf()
                : Immutables.setCopyOf(apiKeyScopes);
    }

    public static PermissionPrincipal user(String accountId, String displayName, String employeeId) {
        return new PermissionPrincipal(accountId, displayName, Kind.USER, employeeId, false, null);
    }

    public static PermissionPrincipal master(String accountId, String displayName, String employeeId) {
        return new PermissionPrincipal(accountId, displayName, Kind.USER, employeeId, true, null);
    }

    public static PermissionPrincipal serviceAccount(String accountId, String displayName,
            Set<String> apiKeyScopes) {
        return new PermissionPrincipal(accountId, displayName, Kind.SERVICE_ACCOUNT, null, false,
                apiKeyScopes);
    }

    public static PermissionPrincipal temporaryMaster(String accountId, String displayName) {
        return new PermissionPrincipal(accountId, displayName, Kind.TEMPORARY_MASTER, null, false, null);
    }

    public String accountId() {
        return accountId;
    }

    public String displayName() {
        return displayName;
    }

    public Kind kind() {
        return kind;
    }

    /** The employee behind this account, or null for a service account. */
    public String employeeId() {
        return employeeId;
    }

    /**
     * Master status.
     *
     * <p>Master is an account flag, not a magic user id, and there may be
     * several. It does <em>not</em> short-circuit this evaluator: sensitive
     * actions still emit approval requests where the approval rules require
     * them, and a master cannot silently self-elevate.
     */
    public boolean isMaster() {
        return master;
    }

    /**
     * Scopes carried by the API key, for a service account.
     *
     * <p>These <em>narrow</em> the account's grants; they never widen them. A
     * key scoped to reads cannot write even if its account may.
     */
    public Set<String> apiKeyScopes() {
        return apiKeyScopes;
    }

    @Override
    public String toString() {
        return kind + "[" + accountId + (displayName == null ? "" : " " + displayName)
                + (master ? " MASTER" : "") + "]";
    }
}
