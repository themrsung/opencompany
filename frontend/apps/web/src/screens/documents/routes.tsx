import type { AnyRoute } from '@tanstack/react-router';
import { pendingRoute, type RootRoute } from '../../router.js';

/** Documents, templates and the font manager. */
export function documentRoutes(parent: RootRoute): AnyRoute[] {
  return [
    pendingRoute(parent, '/documents', 'nav.documents'),
    pendingRoute(parent, '/templates', 'nav.templates'),
    pendingRoute(parent, '/fonts', 'nav.fonts'),
  ];
}
