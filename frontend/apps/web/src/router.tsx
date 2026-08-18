import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  useRouterState,
  type AnyRoute,
} from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { Page } from './layout/AppShell.js';
import { AppChrome } from './session/AppChrome.js';
import { isSignInPath } from './session/paths.js';
import { accountingRoutes } from './screens/accounting/routes.js';
import { adminRoutes } from './screens/admin/routes.js';
import { approvalRoutes } from './screens/approvals/routes.js';
import { attendanceRoutes } from './screens/attendance/routes.js';
import { documentRoutes } from './screens/documents/routes.js';
import { orgRoutes } from './screens/org/routes.js';
import { signinRoutes } from './screens/signin/routes.js';

/**
 * The route tree, declared in code and composed from one module per area.
 *
 * Code rather than a generated filesystem tree: a generated tree is a build
 * artefact that has to be committed and kept in step, and with this many routes
 * the explicit version is easier to review and impossible to get out of date.
 *
 * Composed per area because several people build screens at once, and a single
 * file listing every route is a merge conflict on every branch. Each area
 * exports `routes(parent)` and owns its own file.
 */

/**
 * Sign-in is the one screen that renders without a session and without the
 * shell — no sidebar to a place you cannot go, no support banner about a
 * company you have not proved you belong to. Everything else renders inside
 * `AppChrome`, which owns the session guard and that banner.
 */
function Shell(): React.ReactNode {
  const signIn = useRouterState({ select: (state) => isSignInPath(state.location.pathname) });
  return signIn ? <Outlet /> : <AppChrome />;
}

const rootRoute = createRootRoute({ component: Shell });

/**
 * A route that exists in the navigation but whose screen is not built.
 *
 * Exported so an area module can use it for the screens it has not reached
 * yet: a nav item that 404s is worse than one that says plainly there is
 * nothing here.
 */
export function pendingRoute(parent: typeof rootRoute, path: string, titleKey: string): AnyRoute {
  return createRoute({
    getParentRoute: () => parent,
    path,
    component: function Pending(): React.ReactNode {
      const { t } = useTranslation();
      return (
        <Page title={t(titleKey)}>
          <div className="page__surface" style={{ padding: 'var(--ci-space-5)' }}>
            <p style={{ color: 'var(--ci-fg-muted)', margin: 0 }}>{t('common.empty')}</p>
          </div>
        </Page>
      );
    },
  });
}

export type RootRoute = typeof rootRoute;

const routeTree = rootRoute.addChildren([
  ...approvalRoutes(rootRoute),
  ...attendanceRoutes(rootRoute),
  ...documentRoutes(rootRoute),
  ...orgRoutes(rootRoute),
  ...accountingRoutes(rootRoute),
  ...adminRoutes(rootRoute),
  ...signinRoutes(rootRoute),
]);

export const router = createRouter({ routeTree, defaultPreload: 'intent' });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
