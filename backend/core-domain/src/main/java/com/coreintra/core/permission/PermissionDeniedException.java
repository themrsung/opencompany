package com.coreintra.core.permission;

/**
 * Thrown when a check fails.
 *
 * <p>Carries the full decision, so the API layer can return an RFC 7807
 * problem+json naming the missing permission — and so the audit log records the
 * attempt with its reasoning rather than a bare 403.
 */
public class PermissionDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient PermissionDecision decision;

    public PermissionDeniedException(PermissionDecision decision) {
        super(decision.summary());
        this.decision = decision;
    }

    public PermissionDecision decision() {
        return decision;
    }

    /** The permission the caller lacked, for the problem+json body. */
    public PermissionKey requiredPermission() {
        return decision.key();
    }
}
