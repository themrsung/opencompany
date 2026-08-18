import type { AnyRoute } from '@tanstack/react-router';
import { pendingRoute, type RootRoute } from '../../router.js';

/** The org chart and the effective-permissions explainer. */
export function orgRoutes(parent: RootRoute): AnyRoute[] {
  return [pendingRoute(parent, '/org', 'nav.org')];
}
