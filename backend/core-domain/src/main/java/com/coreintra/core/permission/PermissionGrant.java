package com.coreintra.core.permission;

import java.io.Serializable;

/**
 * One row of permission: a key, a scope, where it came from, and whether it
 * allows or denies.
 *
 * <p>Denies are first-class rather than an absence of a grant. "This rank may
 * read salaries except in the audit unit" is expressible only if a deny can
 * out-rank an allow, and the alternative — enumerating every unit that is not
 * the audit unit — rots the moment someone adds a unit.
 */
public final class PermissionGrant implements Serializable {

    private static final long serialVersionUID = 1L;

    private final PermissionKey key;
    private final PermissionScope scope;
    private final GrantSource source;
    private final String sourceId;
    private final String sourceLabel;
    private final boolean allow;

    private PermissionGrant(PermissionKey key, PermissionScope scope, GrantSource source,
            String sourceId, String sourceLabel, boolean allow) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (scope == null) {
            throw new NullPointerException("scope");
        }
        if (source == null) {
            throw new NullPointerException("source");
        }
        this.key = key;
        this.scope = scope;
        this.source = source;
        this.sourceId = sourceId;
        this.sourceLabel = sourceLabel;
        this.allow = allow;
    }

    public static PermissionGrant allow(PermissionKey key, PermissionScope scope,
            GrantSource source, String sourceId, String sourceLabel) {
        return new PermissionGrant(key, scope, source, sourceId, sourceLabel, true);
    }

    public static PermissionGrant deny(PermissionKey key, PermissionScope scope,
            GrantSource source, String sourceId, String sourceLabel) {
        return new PermissionGrant(key, scope, source, sourceId, sourceLabel, false);
    }

    public PermissionKey key() {
        return key;
    }

    public PermissionScope scope() {
        return scope;
    }

    public GrantSource source() {
        return source;
    }

    /** Identifier of the rank / job function / org unit / account this came from. */
    public String sourceId() {
        return sourceId;
    }

    /** Human-readable source, e.g. "부장" or "회계팀". For the explainer. */
    public String sourceLabel() {
        return sourceLabel;
    }

    public boolean isAllow() {
        return allow;
    }

    public boolean isDeny() {
        return !allow;
    }

    @Override
    public String toString() {
        return (allow ? "ALLOW " : "DENY ") + key + " @" + scope
                + " from " + source + "(" + (sourceLabel == null ? sourceId : sourceLabel) + ")";
    }
}
