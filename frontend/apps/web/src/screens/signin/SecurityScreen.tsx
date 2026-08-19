import type { components } from '@coreintra/api-client';
import { Badge, Banner, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { useSession } from '../../session/session.js';

type EnrolmentStatus = components['schemas']['EnrolmentStatus'];
type Enrolled = components['schemas']['Enrolled'];
type Confirmation = components['schemas']['Confirmation'];
type RecoveryCodes = components['schemas']['RecoveryCodes'];
type ActiveSession = components['schemas']['ActiveSession'];

const LOW_RECOVERY_CODES = 3;

/**
 * Account security: what this account can sign in with, and what is signed in
 * right now.
 *
 * The recovery codes are the whole reason this screen is careful. They are
 * shown exactly once, held in component state and nowhere else, and the only
 * button next to them takes them off the screen for good — because a list of
 * one-time codes left sitting in a tab on a shared desk is the failure this
 * design is trying to prevent.
 */
export function SecurityScreen(): ReactNode {
  const { t } = useTranslation();
  const session = useSession();
  const queryClient = useQueryClient();
  const accountId = session.identity?.accountId ?? '';

  const [codes, setCodes] = useState<readonly string[] | null>(null);
  const [enrolment, setEnrolment] = useState<Enrolled | null>(null);
  const [confirmCode, setConfirmCode] = useState('');
  const [confirmed, setConfirmed] = useState(false);

  const status = useQuery({
    queryKey: ['account', 'enrolment', accountId],
    queryFn: () =>
      api.get<EnrolmentStatus>('/account/enrolment', { query: { accountId } }),
    enabled: accountId !== '',
  });

  const devices = useQuery({
    queryKey: ['auth', 'session', 'active'],
    queryFn: () => api.get<ActiveSession[]>('/auth/session/active'),
  });

  const begin = useMutation({
    mutationFn: () =>
      api.post<Enrolled>('/account/enrolment', undefined, { query: { accountId } }),
    onSuccess: (result) => {
      setEnrolment(result);
      setConfirmed(false);
      setCodes(result.recoveryCodes ?? null);
    },
  });

  const confirm = useMutation({
    mutationFn: (code: string) =>
      api.post<Confirmation>('/account/enrolment/confirmation', { code }),
    onSuccess: (result) => {
      setConfirmed(result.confirmed === true);
      setConfirmCode('');
      if (result.confirmed === true) {
        setEnrolment(null);
      }
      void queryClient.invalidateQueries({ queryKey: ['account', 'enrolment'] });
    },
  });

  const regenerate = useMutation({
    mutationFn: () =>
      api.post<RecoveryCodes>('/account/enrolment/recovery-codes', undefined, {
        query: { accountId },
      }),
    onSuccess: (result) => {
      setCodes(result.recoveryCodes ?? null);
      void queryClient.invalidateQueries({ queryKey: ['account', 'enrolment'] });
    },
  });

  const revokeDevice = useMutation({
    mutationFn: (sessionId: string) =>
      api.request<void>(`/auth/session/active/${encodeURIComponent(sessionId)}`, {
        method: 'DELETE',
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['auth', 'session', 'active'] });
    },
  });

  const remaining = status.data?.remainingRecoveryCodes ?? 0;
  const failure = begin.error ?? confirm.error ?? regenerate.error ?? revokeDevice.error ?? null;

  const columns: readonly Column<ActiveSession>[] = [
    {
      key: 'created',
      header: t('signin.sessionCreated'),
      render: (row) => row.createdAt ?? '—',
    },
    {
      key: 'lastUsed',
      header: t('signin.sessionLastUsed'),
      render: (row) => row.lastUsedAt ?? '—',
    },
    { key: 'ip', header: t('signin.sessionIp'), render: (row) => row.ipAddress ?? '—' },
    { key: 'agent', header: t('signin.sessionAgent'), render: (row) => row.userAgent ?? '—' },
    {
      key: 'revoke',
      header: t('common.action'),
      render: (row) => (
        <Button
          tone="danger"
          busy={revokeDevice.isPending}
          onClick={() => {
            if (row.sessionId !== undefined) {
              revokeDevice.mutate(row.sessionId);
            }
          }}
        >
          {t('signin.sessionRevoke')}
        </Button>
      ),
    },
  ];

  return (
    <Page title={t('signin.security')}>
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '44rem' }}>{t('signin.securityIntro')}</p>

      {failure === null ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(failure, t).message}
        </Banner>
      )}

      {codes === null ? null : (
        <Banner tone="warning" title={t('signin.recoveryTitle')}>
          <p>{t('signin.recoveryOnceWarning')}</p>
          <ul className="ci-numeric" style={{ columns: 2, margin: 'var(--ci-space-3) 0' }}>
            {codes.map((code) => (
              <li key={code}>{code}</li>
            ))}
          </ul>
          <p>{t('signin.recoveryStored')}</p>
          <Button
            tone="primary"
            onClick={() => {
              // Gone from the screen and from memory. There is no second look:
              // that is what "shown once" has to mean to be worth anything.
              setCodes(null);
            }}
          >
            {t('signin.recoveryHide')}
          </Button>
        </Banner>
      )}

      <section style={{ marginTop: 'var(--ci-space-5)' }}>
        <h2>{t('signin.recoveryTitle')}</h2>
        <p>
          {t('signin.recoveryRemaining', { remaining })}{' '}
          {remaining > 0 && remaining < LOW_RECOVERY_CODES ? (
            <Badge tone="warning">{t('signin.recoveryLow')}</Badge>
          ) : null}
        </p>
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('signin.recoveryRegenerateWarning')}</p>
        <Button
          busy={regenerate.isPending}
          onClick={() => {
            regenerate.mutate();
          }}
        >
          {t('signin.recoveryRegenerate')}
        </Button>
      </section>

      {status.data?.emailOtpAvailable === true ? (
        <section style={{ marginTop: 'var(--ci-space-5)' }}>
          <h2>{t('signin.emailOtp')}</h2>
          <p>{t('signin.emailOtpOn')}</p>
        </section>
      ) : null}

      <section style={{ marginTop: 'var(--ci-space-5)' }}>
        <h2>{t('signin.enrolTitle')}</h2>
        <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '44rem' }}>{t('signin.enrolIntro')}</p>

        {confirmed ? <Banner tone="positive">{t('signin.enrolConfirmed')}</Banner> : null}

        {enrolment === null ? (
          <Button
            busy={begin.isPending}
            onClick={() => {
              begin.mutate();
            }}
          >
            {t('signin.enrolStart')}
          </Button>
        ) : (
          <div>
            {/* No QR renderer ships on this box — see the report. Saying so and
                showing the key is honest; drawing an empty square is not. */}
            <p>{t('signin.qrUnavailable')}</p>
            <p>{t('signin.enrolManual')}</p>
            <p className="ci-numeric">
              <code>{enrolment.secretBase32 ?? ''}</code>
            </p>
            <details>
              <summary>{t('signin.qrAlt')}</summary>
              <p style={{ overflowWrap: 'anywhere' }}>
                <code>{enrolment.otpauthUri ?? ''}</code>
              </p>
            </details>
            <p>{t('signin.enrolConfirmHint')}</p>
            <TextField
              label={t('signin.code')}
              hint={t('signin.codeHint')}
              autoComplete="one-time-code"
              inputMode="numeric"
              value={confirmCode}
              onChange={(event) => {
                setConfirmCode(event.target.value);
              }}
            />
            <Button
              tone="primary"
              busy={confirm.isPending}
              disabled={confirmCode.trim() === ''}
              onClick={() => {
                confirm.mutate(confirmCode);
              }}
            >
              {t('signin.enrolConfirm')}
            </Button>
          </div>
        )}
      </section>

      <section style={{ marginTop: 'var(--ci-space-5)' }}>
        <h2>{t('signin.sessionsTitle')}</h2>
        <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '44rem' }}>{t('signin.sessionsIntro')}</p>
        <DataTable
          caption={t('signin.sessionsTitle')}
          columns={columns}
          rows={devices.data ?? []}
          rowKey={(row) => row.sessionId ?? String(row.createdAt)}
          emptyMessage={t('signin.sessionsEmpty')}
        />
      </section>
    </Page>
  );
}
