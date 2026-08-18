import type { components } from '@coreintra/api-client';
import type { TFunction } from 'i18next';
import { Badge, Banner, Button, DataTable, EmptyState, TextField, type Column } from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearch } from '@tanstack/react-router';
import { useCallback, useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { usePrimaryShortcut } from '../../session/keyboard.js';
import {
  AsOfField,
  CompanySelect,
  todayBusinessDate,
  useCompanyChoice,
} from '../../session/viewingContext.js';

type DecisionView = components['schemas']['DecisionView'];
type GrantEntry = components['schemas']['GrantEntry'];
type ConsiderationEntry = components['schemas']['ConsiderationEntry'];
type EffectiveGrantsResponse = components['schemas']['EffectiveGrantsResponse'];

type TargetKind = 'employee' | 'unit' | 'company';

/** Prefilled from the org chart: the roster links here with a person already chosen. */
export interface ExplainerSearch {
  readonly employeeId?: string;
  readonly asOf?: string;
}

export function parseExplainerSearch(search: unknown): ExplainerSearch {
  if (typeof search !== 'object' || search === null) {
    return {};
  }
  const record = search as Record<string, unknown>;
  const employeeId = record['employeeId'];
  const asOf = record['asOf'];
  return {
    ...(typeof employeeId === 'string' && employeeId !== '' ? { employeeId } : {}),
    ...(typeof asOf === 'string' && asOf !== '' ? { asOf } : {}),
  };
}

/**
 * "Why can this user do this?" — §4's explainer.
 *
 * The screen exists because a permission model with deny-by-default, explicit
 * denies and four grant sources is not something anyone can hold in their head
 * during an argument. So it does not summarise: it names the grant that
 * decided, and lists everything the evaluator looked at, including the grants
 * that did not apply and the reason each was passed over.
 *
 * It asks about an *account* and answers as of a *date*, because that is what
 * the server decides on. Both are on the screen rather than assumed.
 */
export function PermissionExplainerScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const initial = parseExplainerSearch(useSearch({ strict: false }));

  const [asOf, setAsOf] = useState(initial.asOf ?? todayBusinessDate());
  const [accountId, setAccountId] = useState('');
  const [permission, setPermission] = useState('');
  const [targetKind, setTargetKind] = useState<TargetKind>(
    initial.employeeId === undefined ? 'company' : 'employee',
  );
  const [employeeId, setEmployeeId] = useState(initial.employeeId ?? '');
  const [orgUnitId, setOrgUnitId] = useState('');
  const [asked, setAsked] = useState<Question | null>(null);

  const choice = useCompanyChoice(asOf);
  const companyId = choice.companyId;

  const canAsk =
    accountId.trim() !== '' &&
    permission.trim() !== '' &&
    (targetKind !== 'employee' || employeeId.trim() !== '') &&
    (targetKind !== 'unit' || orgUnitId.trim() !== '');

  const ask = useCallback(() => {
    if (!canAsk) {
      return;
    }
    setAsked({
      accountId: accountId.trim(),
      permission: permission.trim(),
      targetKind,
      employeeId: employeeId.trim(),
      orgUnitId: orgUnitId.trim(),
      companyId: companyId ?? '',
      asOf,
    });
  }, [accountId, asOf, canAsk, companyId, employeeId, orgUnitId, permission, targetKind]);

  usePrimaryShortcut(canAsk, ask);

  const decision = useQuery({
    queryKey: ['permissions', 'decision', asked],
    enabled: asked !== null,
    queryFn: async () => {
      const question = asked as Question;
      if (question.targetKind === 'employee') {
        return api.get<DecisionView>('/permissions/decision/about-employee', {
          query: {
            accountId: question.accountId,
            permission: question.permission,
            employeeId: question.employeeId,
            asOf: question.asOf,
          },
        });
      }
      return api.get<DecisionView>('/permissions/decision/about-unit', {
        query: {
          accountId: question.accountId,
          permission: question.permission,
          companyId: question.companyId,
          ...(question.targetKind === 'unit' ? { orgUnitId: question.orgUnitId } : {}),
          asOf: question.asOf,
        },
      });
    },
  });

  // The effective list is keyed by employee, not by account: the API splits the
  // two and this screen cannot join them. See the report.
  const effective = useQuery({
    queryKey: ['permissions', 'effective', employeeId, asOf],
    enabled: employeeId.trim() !== '',
    queryFn: async () =>
      api.get<EffectiveGrantsResponse>('/permissions/effective', {
        query: { employeeId: employeeId.trim(), asOf },
      }),
  });

  const onSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    ask();
  };

  return (
    <Page
      title={t('explainer.title')}
      actions={
        <Link to="/org" className="ci-button">
          {t('org.title')}
        </Link>
      }
    >
      <h2 style={{ marginTop: 0 }}>{t('explainer.question')}</h2>
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '44rem' }}>{t('explainer.intro')}</p>

      <form className="page__surface" style={{ padding: 'var(--ci-space-4)' }} onSubmit={onSubmit} noValidate>
        <div style={{ display: 'flex', gap: 'var(--ci-space-4)', flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <TextField
            label={t('explainer.account')}
            hint={t('explainer.accountHint')}
            name="accountId"
            value={accountId}
            onChange={(event) => {
              setAccountId(event.target.value);
            }}
          />
          <TextField
            label={t('explainer.permission')}
            hint={t('explainer.permissionHint')}
            name="permission"
            value={permission}
            onChange={(event) => {
              setPermission(event.target.value);
            }}
          />
          <div className="ci-field">
            <label className="ci-field__label" htmlFor="explainer-target">
              {t('explainer.targetKind')}
            </label>
            <select
              id="explainer-target"
              className="ci-field__input"
              value={targetKind}
              onChange={(event) => {
                setTargetKind(event.target.value as TargetKind);
              }}
            >
              <option value="employee">{t('explainer.targetEmployee')}</option>
              <option value="unit">{t('explainer.targetUnit')}</option>
              <option value="company">{t('explainer.wholeCompany')}</option>
            </select>
          </div>
          {targetKind === 'employee' ? (
            <TextField
              label={t('explainer.employeeId')}
              name="employeeId"
              value={employeeId}
              onChange={(event) => {
                setEmployeeId(event.target.value);
              }}
            />
          ) : null}
          {targetKind === 'unit' ? (
            <TextField
              label={t('explainer.unit')}
              name="orgUnitId"
              value={orgUnitId}
              onChange={(event) => {
                setOrgUnitId(event.target.value);
              }}
            />
          ) : null}
          {targetKind === 'employee' ? null : <CompanySelect choice={choice} language={i18n.language} />}
          <AsOfField
            value={asOf}
            onChange={setAsOf}
            label={t('explainer.asOf')}
            hint={t('explainer.asOfHint')}
          />
        </div>
        <div style={{ marginTop: 'var(--ci-space-4)' }}>
          <Button tone="primary" type="submit" disabled={!canAsk} busy={decision.isFetching}>
            {decision.isFetching ? t('explainer.checking') : t('explainer.check')}
          </Button>
        </div>
      </form>

      {decision.error === null || decision.error === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(decision.error, t).message}
        </Banner>
      )}

      {asked === null ? (
        <EmptyState message={t('explainer.needAccount')} />
      ) : decision.data === undefined ? null : (
        <Decision decision={decision.data} />
      )}

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>{t('explainer.effectiveTitle')}</h2>
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('explainer.effectiveHint')}</p>
        {effective.data === undefined ? (
          <EmptyState message={t('explainer.effectiveEmpty')} />
        ) : (
          <Effective response={effective.data} />
        )}
      </section>
    </Page>
  );
}

interface Question {
  readonly accountId: string;
  readonly permission: string;
  readonly targetKind: TargetKind;
  readonly employeeId: string;
  readonly orgUnitId: string;
  readonly companyId: string;
  readonly asOf: string;
}

function Decision({ decision }: { readonly decision: DecisionView }): ReactNode {
  const { t } = useTranslation();
  const allowed = decision.allowed === true;
  const deciding = decision.decidingGrant;
  const denied = !allowed;
  return (
    <section
      className="page__surface"
      style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}
    >
      <Banner tone={allowed ? 'positive' : 'danger'} title={allowed ? t('explainer.allowedTitle') : t('explainer.deniedTitle')}>
        {decision.summary ?? decision.permission ?? ''}
      </Banner>

      <h3>{t('explainer.deciding')}</h3>
      {deciding === undefined ? (
        <p>{t('explainer.noGrant')}</p>
      ) : (
        <>
          <GrantFacts grant={deciding} />
          {denied && isDeny(deciding) ? (
            <p>
              <Badge tone="danger">{t('explainer.denyWon')}</Badge>{' '}
              <span style={{ color: 'var(--ci-fg-muted)' }}>{t('explainer.denyAlwaysWins')}</span>
            </p>
          ) : null}
        </>
      )}

      <h3>{t('explainer.considerations')}</h3>
      <p style={{ color: 'var(--ci-fg-muted)' }}>{t('explainer.considerationsHint')}</p>
      <Considerations rows={decision.considerations ?? []} />
    </section>
  );
}

function GrantFacts({ grant }: { readonly grant: GrantEntry }): ReactNode {
  const { t } = useTranslation();
  return (
    <dl style={{ display: 'flex', gap: 'var(--ci-space-5)', flexWrap: 'wrap' }}>
      <Fact label={t('explainer.colEffect')} value={effectLabel(grant.effect, t)} />
      <Fact label={t('explainer.colPermission')} value={grant.permission} />
      <Fact label={t('explainer.colScope')} value={grant.scope} />
      <Fact label={t('explainer.attachedTo')} value={sourceLabel(grant, t)} />
      <Fact label={t('explainer.colSourceId')} value={grant.sourceId} />
    </dl>
  );
}

function Fact({ label, value }: { readonly label: string; readonly value: string | undefined }): ReactNode {
  return (
    <div>
      <dt style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>{label}</dt>
      <dd style={{ margin: 0 }}>{value ?? '-'}</dd>
    </div>
  );
}

function Considerations({ rows }: { readonly rows: readonly ConsiderationEntry[] }): ReactNode {
  const { t } = useTranslation();
  const columns: readonly Column<ConsiderationEntry>[] = [
    {
      key: 'applied',
      header: t('explainer.colApplied'),
      render: (row) =>
        row.applied === true ? (
          <Badge tone="accent">{t('explainer.applied')}</Badge>
        ) : (
          <Badge>{t('explainer.notApplied')}</Badge>
        ),
    },
    {
      key: 'effect',
      header: t('explainer.colEffect'),
      render: (row) => effectLabel(row.grant?.effect, t),
    },
    {
      key: 'permission',
      header: t('explainer.colPermission'),
      render: (row) => row.grant?.permission ?? '-',
    },
    { key: 'scope', header: t('explainer.colScope'), render: (row) => row.grant?.scope ?? '-' },
    {
      key: 'source',
      header: t('explainer.colSource'),
      render: (row) => sourceLabel(row.grant, t),
    },
    { key: 'reason', header: t('explainer.reason'), render: (row) => row.reason ?? '-' },
  ];
  return (
    <DataTable
      caption={t('explainer.considerations')}
      columns={columns}
      rows={rows}
      rowKey={(row) => row.grant?.id ?? `${row.grant?.permission ?? ''}:${row.grant?.sourceId ?? ''}`}
      emptyMessage={t('explainer.grantsEmpty')}
    />
  );
}

function Effective({ response }: { readonly response: EffectiveGrantsResponse }): ReactNode {
  const { t } = useTranslation();
  const columns: readonly Column<GrantEntry>[] = [
    { key: 'effect', header: t('explainer.colEffect'), render: (grant) => effectLabel(grant.effect, t) },
    { key: 'permission', header: t('explainer.colPermission'), render: (grant) => grant.permission ?? '-' },
    { key: 'scope', header: t('explainer.colScope'), render: (grant) => grant.scope ?? '-' },
    {
      key: 'source',
      header: t('explainer.colSource'),
      render: (grant) => sourceLabel(grant, t),
    },
    { key: 'sourceId', header: t('explainer.colSourceId'), render: (grant) => grant.sourceId ?? '-' },
  ];
  const scope = [
    ...(response.orgUnitIds ?? []),
    ...(response.rankIds ?? []),
    ...(response.jobFunctionIds ?? []),
  ].join(', ');
  return (
    <>
      {scope === '' ? null : (
        <p style={{ color: 'var(--ci-fg-muted)' }}>{t('explainer.scopeContext', { scope })}</p>
      )}
      <DataTable
        caption={t('explainer.effectiveTitle')}
        columns={columns}
        rows={response.grants ?? []}
        rowKey={(grant) => grant.id ?? `${grant.permission ?? ''}:${grant.sourceId ?? ''}`}
        emptyMessage={t('explainer.effectiveEmpty')}
      />
    </>
  );
}

/**
 * What the grant is attached to, in words.
 *
 * The server's own label wins when it sent one — it knows the rank is 부장 and
 * this screen does not — and the enum is translated only as the fallback.
 */
function sourceLabel(grant: GrantEntry | undefined, t: TFunction): string {
  if (grant === undefined) {
    return '-';
  }
  if (grant.sourceLabel !== undefined && grant.sourceLabel !== '') {
    return grant.sourceLabel;
  }
  switch ((grant.source ?? '').toUpperCase()) {
    case 'RANK':
      return t('explainer.sourceRank');
    case 'JOB_FUNCTION':
      return t('explainer.sourceJobFunction');
    case 'ORG_UNIT':
      return t('explainer.sourceOrgUnit');
    case 'ACCOUNT':
      return t('explainer.sourceAccount');
    default:
      return grant.source ?? '-';
  }
}

function isDeny(grant: GrantEntry): boolean {
  return (grant.effect ?? '').toUpperCase().includes('DENY');
}

function effectLabel(effect: string | undefined, t: TFunction): string {
  if (effect === undefined) {
    return '-';
  }
  return effect.toUpperCase().includes('DENY') ? t('explainer.effectDeny') : t('explainer.effectAllow');
}
