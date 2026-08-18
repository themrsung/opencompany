import { ApiError, type components } from '@coreintra/api-client';
import { Banner, Button, TextField } from '@coreintra/ui';
import { useNavigate, useRouter } from '@tanstack/react-router';
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { useSession } from '../../session/session.js';

type SignedIn = components['schemas']['SignedIn'];

/**
 * §7: there are no passwords. A username, and either the six digits from an
 * authenticator or a one-time recovery code.
 *
 * Two rules shape everything below them:
 *
 *  1. The screen never distinguishes "no such user" from "wrong code". The
 *     server deliberately answers both the same way; wording them differently
 *     here would hand an attacker the account list the server just refused to
 *     give. So every rejection that is not a rate limit or a broken server
 *     lands on one sentence, by one code path, with no extra request in
 *     either case for a stopwatch to notice.
 *  2. Nothing token-shaped is written down. The server sets HttpOnly cookies;
 *     what this screen keeps is a display name, so the app can greet someone
 *     before the first request comes back.
 */
export function SignInScreen(): ReactNode {
  const { t } = useTranslation();
  const session = useSession();
  const navigate = useNavigate();
  const router = useRouter();

  const [username, setUsername] = useState('');
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState(false);
  const [rejected, setRejected] = useState(false);
  const [trouble, setTrouble] = useState<string | null>(null);
  const [waitSeconds, setWaitSeconds] = useState(0);
  const [busy, setBusy] = useState(false);

  const locked = waitSeconds > 0;
  const signedIn = session.status === 'signed-in';

  // Already signed in — someone typed the URL, or came back on a live session.
  useEffect(() => {
    if (signedIn) {
      void navigate({ to: '/', replace: true });
    }
  }, [signedIn, navigate]);

  useEffect(() => {
    if (!locked) {
      return undefined;
    }
    const id = globalThis.setInterval(() => {
      setWaitSeconds((seconds) => (seconds <= 1 ? 0 : seconds - 1));
    }, 1_000);
    return () => {
      globalThis.clearInterval(id);
    };
  }, [locked]);

  const submit = useCallback(async () => {
    if (busy || locked) {
      return;
    }
    setBusy(true);
    setRejected(false);
    setTrouble(null);
    try {
      const identity = await api.post<SignedIn>('/auth/session', { username, code, recovery });
      const next = session.pendingPath;
      session.signIn(identity);
      session.clearPendingPath();
      if (next === null) {
        await navigate({ to: '/', replace: true });
      } else {
        // An arbitrary remembered path, not a literal the typed router knows
        // at build time.
        router.history.replace(next);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 429) {
        // Progressive lockout. `Retry-After` is the server's number; the fall
        // back only exists because a proxy can strip the header.
        setWaitSeconds(error.retryAfterSeconds ?? 60);
      } else if (error instanceof ApiError && (error.status === 0 || error.status >= 500)) {
        // Not an authentication answer at all, and saying "wrong code" when
        // the box is unreachable sends someone hunting for the wrong problem.
        setTrouble(presentError(error, t).message);
      } else {
        setRejected(true);
      }
      setCode('');
    } finally {
      setBusy(false);
    }
  }, [busy, locked, username, code, recovery, session, navigate, router, t]);

  const onSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    void submit();
  };

  const canSubmit = !busy && !locked && username.trim() !== '' && code.trim() !== '';

  return (
    <main className="page__surface" style={{ maxWidth: '28rem', margin: '0 auto' }}>
      <h1 className="page__title">{t('signin.title')}</h1>
      <p style={{ color: 'var(--ci-fg-muted)' }}>{t('signin.intro')}</p>

      {session.expired ? <Banner tone="warning">{t('signin.expired')}</Banner> : null}

      {locked ? (
        <Banner tone="warning" title={t('signin.lockedTitle')}>
          <p>{t('signin.lockedBody', { seconds: waitSeconds })}</p>
          <p>{t('signin.lockedWhy')}</p>
        </Banner>
      ) : null}

      {trouble === null ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {trouble}
        </Banner>
      )}

      <form onSubmit={onSubmit} noValidate>
        <TextField
          label={t('signin.username')}
          hint={t('signin.usernameHint')}
          name="username"
          autoComplete="username"
          autoFocus
          value={username}
          onChange={(event) => {
            setUsername(event.target.value);
          }}
        />
        <TextField
          label={recovery ? t('signin.recoveryCode') : t('signin.code')}
          hint={recovery ? t('signin.recoveryCodeHint') : t('signin.codeHint')}
          name="code"
          autoComplete="one-time-code"
          inputMode={recovery ? 'text' : 'numeric'}
          value={code}
          onChange={(event) => {
            setCode(event.target.value);
          }}
        />

        {rejected ? (
          <p className="ci-field__error" role="alert">
            {t('signin.failed')} {t('signin.failedWhy')}
          </p>
        ) : null}

        <div className="flex items-center gap-4" style={{ marginTop: 'var(--ci-space-4)' }}>
          <Button tone="primary" busy={busy} disabled={!canSubmit} type="submit">
            {locked
              ? t('signin.lockedCountdown', { seconds: waitSeconds })
              : busy
                ? t('signin.submitting')
                : t('signin.submit')}
          </Button>
          <button
            type="button"
            className="ci-button"
            onClick={() => {
              setRecovery((was) => !was);
              setCode('');
            }}
          >
            {recovery ? t('signin.useAuthenticator') : t('signin.useRecovery')}
          </button>
        </div>
      </form>

      <section style={{ marginTop: 'var(--ci-space-5)' }}>
        <h2>{t('signin.noPasswords')}</h2>
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('signin.noPasswordsBody')}</p>
        <h2>{t('signin.deviceLost')}</h2>
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('signin.deviceLostBody')}</p>
      </section>
    </main>
  );
}
