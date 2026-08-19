import { createRoute, type AnyRoute } from '@tanstack/react-router';
import type { RootRoute } from '../../router.js';
import { AuditScreen } from './AuditScreen.js';
import { SupportReportScreen } from './SupportReportScreen.js';
import { SupportScreen } from './SupportScreen.js';

/** The audit log and the temporary-master flow. */
export function adminRoutes(parent: RootRoute): AnyRoute[] {
  return [
    createRoute({ getParentRoute: () => parent, path: '/audit', component: AuditScreen }),
    createRoute({ getParentRoute: () => parent, path: '/support', component: SupportScreen }),
    createRoute({
      getParentRoute: () => parent,
      path: '/support/report/$grantId',
      component: SupportReportScreen,
    }),
  ];
}
