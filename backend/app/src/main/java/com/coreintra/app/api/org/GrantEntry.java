package com.coreintra.app.api.org;

import com.coreintra.core.permission.PermissionGrant;

/**
 * One grant on the wire, with the id needed to take it back.
 *
 * <p>Named distinctly from the explainer's {@code GrantView} rather than shared
 * with it. Two DTOs with the same simple name would collide in the generated
 * OpenAPI schema, and the shapes genuinely differ: the explainer shows grants as
 * evidence in a decision, where an id is noise, and this one shows them as rows
 * in an administration screen, where the id is the whole point.
 *
 * <p>{@code effect} is a string rather than a boolean because a deny is not the
 * absence of an allow — it out-ranks every allow within its scope — and
 * {@code "allow": false} reads like a missing grant rather than a deliberate one.
 */
public class GrantEntry {

    private final String id;
    private final String permission;
    private final String scope;
    private final String source;
    private final String sourceId;
    private final String sourceLabel;
    private final String effect;

    GrantEntry(String id, String permission, String scope, String source, String sourceId,
            String sourceLabel, String effect) {
        this.id = id;
        this.permission = permission;
        this.scope = scope;
        this.source = source;
        this.sourceId = sourceId;
        this.sourceLabel = sourceLabel;
        this.effect = effect;
    }

    static GrantEntry from(PermissionGrant grant, String id) {
        return new GrantEntry(id, String.valueOf(grant.key()), grant.scope().name(),
                grant.source().name(), grant.sourceId(), grant.sourceLabel(),
                grant.isAllow() ? "ALLOW" : "DENY");
    }

    /**
     * The revocable row id.
     *
     * <p>Null when it could not be resolved, which means the grant was revoked
     * between the read and this line. Null rather than an invented value: a
     * client that tried to revoke it would otherwise get a confusing "no such
     * grant" for a row it can see.
     */
    public String getId() {
        return id;
    }

    /** {@code resource:action}, e.g. {@code accounting.entry:post}. */
    public String getPermission() {
        return permission;
    }

    /** SELF, ORG_UNIT, ORG_UNIT_SUBTREE, COMPANY or ALL. */
    public String getScope() {
        return scope;
    }

    /** RANK, JOB_FUNCTION, ORG_UNIT or USER_ACCOUNT. */
    public String getSource() {
        return source;
    }

    public String getSourceId() {
        return sourceId;
    }

    /** What to show a human: the 직급 label, the 직무, the unit name, the username. */
    public String getSourceLabel() {
        return sourceLabel;
    }

    /** ALLOW or DENY. A DENY beats every ALLOW within its own scope. */
    public String getEffect() {
        return effect;
    }
}
