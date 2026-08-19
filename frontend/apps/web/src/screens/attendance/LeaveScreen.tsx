import {
  Amount,
  Banner,
  BusinessInstantText,
  DataTable,
  EmptyState,
  TextField,
  type Column,
} from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import type { TFunction } from 'i18next';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import {
  attendance,
  attendanceKeys,
  readInstant,
  shiftDate,
  todayBusinessDate,
  type LeaveTransaction,
} from './queries.js';
import { useCompanyChoice } from './useCompanyChoice.js';
import './attendance.css';

/**
 * 휴가 — the balance, where the days went, and the rules they were computed
 * under.
 *
 * §5 makes accrual configuration rather than statute, so nothing here is
 * computed in the browser: the balance is the server's, and what this screen
 * adds is the *ledger* behind it — every grant, use, carry-over and expiry,
 * with its effective date, its expiry date and its reason. A number nobody can
 * check is the thing a leave screen must not be.
 *
 * Two gaps show through, and both are named on screen rather than hidden:
 * every leave endpoint requires a `policyId` that no endpoint will list, and
 * there is no accrual-policy resource at all — the closest readable statement
 * of the rules in force is the 취업규칙 version, which is shown beside the
 * ledger.
 */
export function LeaveScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const { companies, companyId, choose } = useCompanyChoice();
  const [employeeId, setEmployeeId] = useState('');
  const [policyId, setPolicyId] = useState('');
  const [asOf, setAsOf] = useState(todayBusinessDate);
  const [horizon, setHorizon] = useState(() => shiftDate(todayBusinessDate(), 90));

  const company = companyId ?? '';
  const scoped = company !== '' && employeeId !== '' && policyId !== '';

  const roster = useQuery({
    queryKey: attendanceKeys.employees(company),
    queryFn: () => attendance.employees(company),
    enabled: company !== '',
    staleTime: 5 * 60_000,
  });

  const balance = useQuery({
    queryKey: attendanceKeys.leaveBalance(company, employeeId, policyId, asOf),
    queryFn: () => attendance.leaveBalance(company, employeeId, policyId, asOf),
    enabled: scoped,
  });

  const ledger = useQuery({
    queryKey: attendanceKeys.leaveLedger(company, employeeId, policyId, asOf),
    queryFn: () => attendance.leaveLedger(company, employeeId, policyId, asOf),
    enabled: scoped,
  });

  const expiring = useQuery({
    queryKey: attendanceKeys.leaveExpiring(company, employeeId, policyId, horizon),
    queryFn: () => attendance.leaveExpiring(company, employeeId, policyId, horizon),
    enabled: scoped,
  });

  const rules = useQuery({
    queryKey: attendanceKeys.employmentRules(company, asOf),
    queryFn: () => attendance.employmentRules(company, asOf),
    enabled: company !== '',
    retry: false,
  });

  const amountLabels = useMemo(
    () => ({ roundedNotice: (exact: string) => t('common.roundedNotice', { exact }) }),
    [t],
  );
  const english = i18n.language.startsWith('en');
  const transactions = ledger.data?.transactions ?? [];

  return (
    <Page
      title={t('nav.leave')}
      actions={
        companies.length > 1 ? (
          <label className="ci-field whosin-control">
            <span className="ci-field__label">{t('common.company')}</span>
            <select
              className="ci-field__input"
              value={companyId ?? ''}
              onChange={(event) => {
                choose(event.target.value);
              }}
            >
              {companies.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.nameKo ?? item.nameEn ?? item.id}
                </option>
              ))}
            </select>
          </label>
        ) : undefined
      }
    >
      <div className="whosin-controls">
        <label className="ci-field whosin-control">
          <span className="ci-field__label">{t('common.employee')}</span>
          <select
            className="ci-field__input"
            value={employeeId}
            onChange={(event) => {
              setEmployeeId(event.target.value);
            }}
          >
            <option value="">{t('leave.choosePerson')}</option>
            {(roster.data ?? []).map((employee) => (
              <option key={employee.id} value={employee.id}>
                {english
                  ? (employee.nameEn ?? employee.nameKo ?? employee.id)
                  : (employee.nameKo ?? employee.nameEn ?? employee.id)}
              </option>
            ))}
          </select>
        </label>

        <TextField
          label={t('leave.policyLabel')}
          hint={t('leave.policyHint')}
          value={policyId}
          className="whosin-control"
          onChange={(event) => {
            setPolicyId(event.target.value);
          }}
        />

        <label className="ci-field whosin-control">
          <span className="ci-field__label">{t('businessTime.businessDate')}</span>
          <input
            type="date"
            className="ci-field__input ci-numeric"
            value={asOf}
            onChange={(event) => {
              setAsOf(event.target.value);
            }}
          />
        </label>

        <label className="ci-field whosin-control">
          <span className="ci-field__label">{t('leave.horizonLabel')}</span>
          <input
            type="date"
            className="ci-field__input ci-numeric"
            value={horizon}
            onChange={(event) => {
              setHorizon(event.target.value);
            }}
          />
        </label>
      </div>

      {balance.isError ? (
        <Banner tone="danger" title={presentError(balance.error, t).message}>
          {t('error.unexpected')}
        </Banner>
      ) : null}

      {scoped ? (
        <section className="page__surface leave-balance" aria-labelledby="leave-balance">
          <h2 id="leave-balance" className="leave-balance__title">
            {t('leave.balanceTitle')}
          </h2>
          <p className="leave-balance__figure">
            {balance.data?.balanceDays === undefined ? (
              '—'
            ) : (
              <Amount value={balance.data.balanceDays} displayDecimals={2} labels={amountLabels} />
            )}
          </p>
          <p className="att-muted">{t('leave.asOf', { date: balance.data?.asOf ?? asOf })}</p>
        </section>
      ) : (
        <EmptyState message={employeeId === '' ? t('leave.employeeMissing') : t('leave.policyMissing')} />
      )}

      {scoped ? (
        <section className="page__surface leave-section" aria-labelledby="leave-ledger">
          <h2 id="leave-ledger" className="leave-section__title">
            {t('leave.ledgerTitle')}
          </h2>
          <DataTable<LeaveTransaction>
            caption={t('leave.ledgerTitle')}
            emptyMessage={t('leave.ledgerEmpty')}
            rows={transactions}
            rowKey={(row) => row.id ?? `${row.kind ?? ''}:${row.occurredAt ?? ''}`}
            columns={ledgerColumns(t, amountLabels)}
          />
        </section>
      ) : null}

      {scoped ? (
        <section className="page__surface leave-section" aria-labelledby="leave-expiring">
          <h2 id="leave-expiring" className="leave-section__title">
            {t('leave.expiringTitle')}
          </h2>
          <p className="att-muted">{t('leave.expiringBy', { date: horizon })}</p>
          <DataTable<LeaveTransaction>
            caption={t('leave.expiringTitle')}
            emptyMessage={t('leave.expiringEmpty')}
            rows={expiring.data?.grants ?? []}
            rowKey={(row) => row.id ?? `${row.expiresOn ?? ''}`}
            columns={expiringColumns(t, amountLabels)}
          />
        </section>
      ) : null}

      <section className="page__surface leave-section" aria-labelledby="leave-rules">
        <h2 id="leave-rules" className="leave-section__title">
          {t('leave.rulesTitle')}
        </h2>
        {rules.data === undefined ? (
          <EmptyState message={t('leave.rulesEmpty')} />
        ) : (
          <>
            <p className="att-muted">
              {t('leave.rulesVersion', { version: rules.data.version ?? 0 })} ·{' '}
              {t('leave.rulesEffective', { date: rules.data.effectiveFrom ?? '—' })} ·{' '}
              {t('leave.rulesApprovedUnder', { mode: rules.data.approvedUnderMode ?? '—' })}
            </p>
            <ol className="leave-rules">
              {(rules.data.sections ?? []).map((section) => (
                <li key={section.number ?? section.headingKo}>
                  <h3 className="leave-rules__heading">
                    {section.number === undefined ? null : (
                      <span className="ci-numeric">{section.number} </span>
                    )}
                    {english
                      ? (section.headingEn ?? section.headingKo ?? '')
                      : (section.headingKo ?? section.headingEn ?? '')}
                  </h3>
                  <p className="leave-rules__body">
                    {english
                      ? (section.bodyEn ?? section.bodyKo ?? '')
                      : (section.bodyKo ?? section.bodyEn ?? '')}
                  </p>
                </li>
              ))}
            </ol>
          </>
        )}
      </section>
    </Page>
  );
}

const KINDS = ['GRANT', 'CARRY_OVER', 'USE', 'EXPIRY', 'ADJUSTMENT', 'CANCELLATION'] as const;

function kindLabel(kind: string | undefined, t: TFunction): string {
  return KINDS.some((candidate) => candidate === kind) && kind !== undefined
    ? t(`leave.kind.${kind}`)
    : (kind ?? '—');
}

interface AmountLabels {
  readonly roundedNotice: (exact: string) => string;
}

/**
 * The ledger, column by column.
 *
 * Days go through `<Amount>` like money does: `1.5` and `0.25` are exact
 * decimal strings, the balance is computed from them server-side, and a screen
 * that rounded a quarter-day for display would be lying about somebody's leave.
 * `signedDays` rather than `days`, so the direction is the server's taxonomy
 * and not this screen's guess.
 */
function ledgerColumns(t: TFunction, amountLabels: AmountLabels): Array<Column<LeaveTransaction>> {
  return [
    { key: 'kind', header: t('leave.columnKind'), render: (row) => kindLabel(row.kind, t) },
    {
      key: 'days',
      header: t('leave.columnDays'),
      numeric: true,
      render: (row) =>
        row.signedDays === undefined ? (
          '—'
        ) : (
          <Amount value={row.signedDays} displayDecimals={2} signed labels={amountLabels} />
        ),
    },
    {
      key: 'effective',
      header: t('leave.columnEffective'),
      numeric: true,
      render: (row) => row.effectiveFrom ?? '—',
    },
    {
      key: 'expires',
      header: t('leave.columnExpires'),
      numeric: true,
      render: (row) => row.expiresOn ?? '—',
    },
    {
      key: 'occurred',
      header: t('leave.columnOccurred'),
      numeric: true,
      render: (row) => {
        const instant = readInstant(row.occurredAt);
        return instant === null ? '—' : <BusinessInstantText value={instant} />;
      },
    },
    { key: 'reason', header: t('common.reason'), render: (row) => row.reason ?? '—' },
    {
      key: 'source',
      header: t('leave.columnSource'),
      render: (row) => row.sourceDocumentId ?? '—',
    },
  ];
}

function expiringColumns(
  t: TFunction,
  amountLabels: AmountLabels,
): Array<Column<LeaveTransaction>> {
  return [
    {
      key: 'days',
      header: t('leave.columnDays'),
      numeric: true,
      render: (row) =>
        row.days === undefined ? (
          '—'
        ) : (
          <Amount value={row.days} displayDecimals={2} labels={amountLabels} />
        ),
    },
    {
      key: 'expires',
      header: t('leave.columnExpires'),
      numeric: true,
      render: (row) => row.expiresOn ?? '—',
    },
    {
      key: 'effective',
      header: t('leave.columnEffective'),
      numeric: true,
      render: (row) => row.effectiveFrom ?? '—',
    },
  ];
}
