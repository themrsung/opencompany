package com.coreintra.core.permission;

/**
 * The single authorisation gate.
 *
 * <p>REST controllers, MCP tools, module service accounts and scheduled jobs
 * all route through this interface. There is no "internal" path that skips it,
 * no {@code SYSTEM} principal that is assumed to be allowed, and no
 * convenience method that takes a URL instead of a target. An ArchUnit rule
 * fails the build if a controller or an MCP tool reaches a repository without
 * passing through here first.
 *
 * <p>Every decision is made on the domain object — row-level, not route-level.
 */
public interface PermissionEvaluator {

    /**
     * Decides, and explains.
     *
     * <p>Never throws for an ordinary denial: a denial is a value, because the
     * explainer endpoint needs to show denials without catching exceptions. Use
     * {@link PermissionDecision#orThrow()} at call sites that want the failure.
     */
    PermissionDecision check(PermissionPrincipal principal, PermissionKey key, PermissionTarget target);

    /** Convenience for the common {@code resource:action} literal form. */
    PermissionDecision check(PermissionPrincipal principal, String resource, String action,
            PermissionTarget target);

    /**
     * Everything this principal could do, as of a date — the explainer's list view.
     *
     * <p>Answers "what can this person do?" where {@link #check} answers "why
     * can they do this one thing?".
     */
    EffectivePermissions effectivePermissions(PermissionPrincipal principal,
            java.time.LocalDate asOfBusinessDate);
}
