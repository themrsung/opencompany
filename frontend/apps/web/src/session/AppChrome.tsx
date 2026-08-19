import { Button } from '@coreintra/ui';
import { Link, useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { AppShell, type SupportSession } from '../layout/AppShell.js';
import { NAVIGATION } from '../navigation.js';
import { businessInstantLabel } from './instants.js';
import { useSession } from './session.js';
import { SIGN_IN_PATH } from './paths.js';
import { useLiveSupportSessions, useRevokeSupportSession } from './supportSession.js';

/**
 * Everything that is not the sign-in screen renders inside here: the guard,
 * the shell, and the support banner.
 *
 * The banner is deliberately owned at this level rather than by a screen. §8
 * requires that *every* user sees it while an outsider is inside, and a banner
 * a screen has to remember to render is a banner that will be missing from the
 * screen someone adds next month.
 */
export function AppChrome(): ReactNode {
  const { t } = useTranslation();
  const session = useSession();
  const signedIn = session.status === 'signed-in';

  const live = useLiveSupportSessions(signedIn);
  const revoke = useRevokeSupportSession();

  // One banner. Two concurrent support sessions is not a normal state, and
  // stacking banners would push the page down further than the thing people
  // came to read; the support screen lists all of them.
  const current = live.data?.[0];
  const support: SupportSession | undefined =
    current === undefined
      ? undefined
      : {
          engineerName: current.engineerName ?? t('support.engineerName'),
          capabilities: current.capabilities ?? [],
          expiresAt: businessInstantLabel(current.expiresAt),
          canRevoke: current.revocableByYou === true,
          onRevoke: () => {
            const grantId = current.grantId;
            if (grantId !== undefined && !revoke.isPending) {
              revoke.mutate(grantId);
            }
          },
        };

  return (
    <RequireSession>
      <AppShell
        nav={NAVIGATION.map((item) => ({ to: item.to, label: t(item.labelKey) }))}
        header={<AppHeader />}
        // Not wired: nothing in the API says "the account you are signed in as
        // is a temporary master". See the report — this is the one §8 surface
        // that could not be built honestly, and a guess here would be worse
        // than an absence.
        viewerIsTemporaryMaster={false}
        {...(support === undefined ? {} : { supportSession: support })}
      />
    </RequireSession>
  );
}

function AppHeader(): ReactNode {
  const { t } = useTranslation();
  const session = useSession();
  const navigate = useNavigate();
  const name = session.identity?.displayName ?? '';

  return (
    <div className="flex items-center justify-end gap-4">
      <span style={{ color: 'var(--ci-fg-muted)' }}>{t('app.signedInAs', { name })}</span>
      <Link to="/security" style={{ color: 'var(--ci-accent)' }}>
        {t('signin.security')}
      </Link>
      <Button
        onClick={() => {
          void session.signOut().then(() => navigate({ to: SIGN_IN_PATH, replace: true }));
        }}
      >
        {t('app.signOut')}
      </Button>
    </div>
  );
}

/**
 * The guard. Sends people to sign-in and remembers where they were going, so
 * that a link pasted into chat still lands on the right screen after the
 * detour — including when the detour was a session expiring mid-read.
 */
function RequireSession({ children }: { readonly children: ReactNode }): ReactNode {
  const { t } = useTranslation();
  const session = useSession();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const { status, rememberPath } = session;

  useEffect(() => {
    if (status === 'signed-out') {
      rememberPath(pathname);
      void navigate({ to: SIGN_IN_PATH, replace: true });
    }
  }, [status, pathname, rememberPath, navigate]);

  if (status === 'signed-in') {
    return <>{children}</>;
  }

  // 'unknown' is the boot probe, which is one request long. Rendering the
  // shell here would flash a sidebar and an empty screen at someone who is
  // about to be sent to sign-in.
  return (
    <div className="page__surface" style={{ padding: 'var(--ci-space-5)' }} role="status">
      <p style={{ color: 'var(--ci-fg-muted)', margin: 0 }}>
        {status === 'unknown' ? t('app.loading') : t('signin.signingOut')}
      </p>
    </div>
  );
}
