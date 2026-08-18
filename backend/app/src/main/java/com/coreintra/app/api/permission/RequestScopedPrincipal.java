package com.coreintra.app.api.permission;

import com.coreintra.core.permission.PermissionPrincipal;
import org.springframework.stereotype.Component;

/**
 * Holds the authenticated caller for the duration of one request.
 *
 * <p>A {@link ThreadLocal} rather than a Spring request scope, so the same
 * mechanism serves the MCP server and scheduled jobs, which have no HTTP
 * request to scope to.
 *
 * <p>The authentication filter sets it and, critically, clears it in a
 * {@code finally}. A leaked principal on a pooled request thread would let the
 * next request inherit the previous caller's authority — the single worst
 * failure mode available to this class, which is why {@link #clear()} exists
 * and why the filter that forgets it is a bug, not a leak.
 */
@Component
public class RequestScopedPrincipal implements CurrentPrincipal {

    private static final ThreadLocal<PermissionPrincipal> CURRENT =
            new ThreadLocal<PermissionPrincipal>();

    @Override
    public PermissionPrincipal require() {
        PermissionPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new UnauthenticatedException();
        }
        return principal;
    }

    /** Null when the request is anonymous. */
    public PermissionPrincipal peek() {
        return CURRENT.get();
    }

    public void set(PermissionPrincipal principal) {
        CURRENT.set(principal);
    }

    /** Must be called in a finally block by whoever called {@link #set}. */
    public void clear() {
        CURRENT.remove();
    }
}
