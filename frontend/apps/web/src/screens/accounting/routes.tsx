import type { AnyRoute } from '@tanstack/react-router';
import { pendingRoute, type RootRoute } from '../../router.js';

/** The ledger and its reports. Absent entirely when the module is switched off. */
export function accountingRoutes(parent: RootRoute): AnyRoute[] {
  return [pendingRoute(parent, '/accounting', 'nav.accounting')];
}
