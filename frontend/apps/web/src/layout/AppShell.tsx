import { Badge, Banner, Button } from '@coreintra/ui';
import { Link, Outlet } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * A live vendor support session, as the whole company sees it.
 *
 * §8 requires that while a temporary master session is live, **every user in
 * the company** sees a persistent, non-dismissible banner naming who is
 * connected, which capabilities are live and when it expires, with a Revoke
 * button any master can press. The banner has no dismiss affordance at all —
 * not a hidden one, not a remembered preference — because "impossible to miss"
 * is the requirement and a dismissible banner is missable by definition.
 */
export interface SupportSession {
  readonly engineerName: string;
  readonly capabilities: readonly string[];
  readonly expiresAt: string;
  readonly canRevoke: boolean;
  readonly onRevoke: () => void;
}

export interface NavItem {
  readonly to: string;
  readonly label: string;
  /** A count worth interrupting for. Omitted rather than zero: a "0" badge is noise. */
  readonly badge?: number;
}

export interface AppShellProps {
  readonly nav: readonly NavItem[];
  readonly supportSession?: SupportSession;
  /** True when the *viewer* is the support engineer, not merely watching one. */
  readonly viewerIsTemporaryMaster?: boolean;
  readonly header?: ReactNode;
}

export function AppShell({
  nav,
  supportSession,
  viewerIsTemporaryMaster = false,
  header,
}: AppShellProps): ReactNode {
  const { t } = useTranslation();

  return (
    <div className={`app-shell${viewerIsTemporaryMaster ? ' app-shell--support' : ''}`}>
      {viewerIsTemporaryMaster ? (
        <div className="app-support-strip app-shell__banner" role="note">
          {t('temporaryMaster.title')}
        </div>
      ) : null}

      {supportSession ? (
        <div className="app-shell__banner" style={{ padding: 'var(--ci-space-2)' }}>
          <Banner
            tone="support"
            title={t('temporaryMaster.live')}
            actions={
              supportSession.canRevoke ? (
                <Button tone="danger" onClick={supportSession.onRevoke}>
                  {t('action.revokeNow')}
                </Button>
              ) : undefined
            }
          >
            {t('temporaryMaster.banner', {
              name: supportSession.engineerName,
              capabilities: supportSession.capabilities.join(', '),
              expires: supportSession.expiresAt,
            })}
          </Banner>
        </div>
      ) : null}

      <nav className="app-shell__sidebar" aria-label={t('app.name')}>
        <div className="app-nav">
          {nav.map((item) => (
            <Link key={item.to} to={item.to} className="app-nav__link">
              <span className="app-nav__label">{item.label}</span>
              {item.badge !== undefined && item.badge > 0 ? (
                <span className="app-nav__badge">
                  <Badge tone="accent">{item.badge}</Badge>
                </span>
              ) : null}
            </Link>
          ))}
        </div>
      </nav>

      <main className="app-shell__main">
        {header}
        <Outlet />
      </main>
    </div>
  );
}

export function Page({
  title,
  actions,
  children,
}: {
  readonly title: string;
  readonly actions?: ReactNode;
  readonly children: ReactNode;
}): ReactNode {
  return (
    <>
      <div className="page__header">
        <h1 className="page__title">{title}</h1>
        {actions ? <div>{actions}</div> : null}
      </div>
      {children}
    </>
  );
}
