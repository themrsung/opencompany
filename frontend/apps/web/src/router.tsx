import { createRootRoute, createRoute, createRouter } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { AppShell, Page } from './layout/AppShell.js';

/**
 * The route tree, declared in code rather than generated from the filesystem.
 *
 * A generated route tree is a build artefact that has to be committed and kept
 * in step; with this many routes and one team, the explicit tree is easier to
 * review and impossible to get out of date.
 */

function Shell(): React.ReactNode {
  const { t } = useTranslation();
  return (
    <AppShell
      nav={[
        { to: '/', label: t('nav.inbox') },
        { to: '/whos-in', label: t('nav.whosIn') },
        { to: '/documents', label: t('nav.documents') },
        { to: '/templates', label: t('nav.templates') },
        { to: '/leave', label: t('nav.leave') },
        { to: '/org', label: t('nav.org') },
        { to: '/accounting', label: t('nav.accounting') },
        { to: '/fonts', label: t('nav.fonts') },
        { to: '/audit', label: t('nav.audit') },
        { to: '/support', label: t('nav.support') },
      ]}
    />
  );
}

const rootRoute = createRootRoute({ component: Shell });

/** A page that exists in the nav but whose screen is not built yet. */
function pending(titleKey: string) {
  return function Pending(): React.ReactNode {
    const { t } = useTranslation();
    return (
      <Page title={t(titleKey)}>
        <div className="page__surface" style={{ padding: 'var(--ci-space-5)' }}>
          <p style={{ color: 'var(--ci-fg-muted)', margin: 0 }}>{t('common.empty')}</p>
        </div>
      </Page>
    );
  };
}

function page(path: string, titleKey: string) {
  return createRoute({ getParentRoute: () => rootRoute, path, component: pending(titleKey) });
}

const routeTree = rootRoute.addChildren([
  // The approval inbox *is* the home page rather than a redirect from it. It is
  // the screen people open the product for, and a redirect would only add a
  // history entry between them and it.
  page('/', 'nav.inbox'),
  page('/whos-in', 'nav.whosIn'),
  page('/documents', 'nav.documents'),
  page('/templates', 'nav.templates'),
  page('/leave', 'nav.leave'),
  page('/org', 'nav.org'),
  page('/accounting', 'nav.accounting'),
  page('/fonts', 'nav.fonts'),
  page('/audit', 'nav.audit'),
  page('/support', 'nav.support'),
]);

export const router = createRouter({ routeTree, defaultPreload: 'intent' });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
