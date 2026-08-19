package com.coreintra.app.api.permission;

import com.coreintra.core.permission.PermissionPrincipal;

/**
 * The authenticated caller for the current request.
 *
 * <p>An interface rather than a static holder so that authentication can be
 * swapped (session cookie, API key, MCP token) without any controller
 * changing, and so tests can supply a principal without standing up a filter.
 *
 * <p>{@link #require()} throws rather than returning a fallback. An anonymous
 * default here would silently become "whatever the anonymous principal is
 * allowed to do", which is how deny-by-default systems acquire holes.
 */
public interface CurrentPrincipal {

    /**
     * @return the caller
     * @throws UnauthenticatedException if the request carries no valid credential
     */
    PermissionPrincipal require();

    /** Thrown when a request that needs a caller does not have one. Maps to 401. */
    class UnauthenticatedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnauthenticatedException() {
            super("This request requires an authenticated caller.");
        }
    }
}
