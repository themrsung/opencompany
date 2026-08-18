import type { AnyRoute } from '@tanstack/react-router';
import { pendingRoute, type RootRoute } from '../../router.js';

/** The audit log and the temporary-master flow. */
export function adminRoutes(parent: RootRoute): AnyRoute[] {
  return [
    pendingRoute(parent, '/audit', 'nav.audit'),
    pendingRoute(parent, '/support', 'nav.support'),
  ];
}
