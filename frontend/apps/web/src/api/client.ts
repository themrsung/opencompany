import { ApiClient } from '@coreintra/api-client';

/**
 * The one client instance the app uses.
 *
 * A module-level singleton rather than a context value, deliberately: the
 * single-flight refresh only works if every request in the tab shares one
 * client. Two clients would refresh twice at once, and two concurrent
 * rotations trip the reuse detection and sign the user out — the exact bug
 * rotation exists to prevent.
 */

type SessionExpiredHandler = () => void;

let onExpired: SessionExpiredHandler | null = null;

/**
 * Registered by the app shell once it can route to the sign-in screen.
 *
 * Indirection rather than an import, because the client is created before the
 * router exists and reaching for the router from module scope would make the
 * import order load-bearing.
 */
export function onSessionExpired(handler: SessionExpiredHandler): void {
  onExpired = handler;
}

export const api = new ApiClient({
  baseUrl: '/api/v1',
  onSessionExpired: () => {
    onExpired?.();
  },
});
