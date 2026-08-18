/**
 * The reports.
 *
 * Two things run through all of them. Every figure goes through `<Money>`, so
 * the currency's precision is applied once and the exact stored value stays one
 * keystroke away. And the integrity flags — `balanced` on the trial balance,
 * the balance sheet and the equity statement, `reconciles` on the cash flow —
 * are alarms, not decoration. When one is false the screen says so at the top,
 * in red, with the exact difference, and it does not go on to present a tidy
 * total as though nothing were wrong. Nothing here plugs anything.
 *
 * Each report runs its own query with its own response type. One shared query
 * returning `unknown` and a cast per branch would compile just as well and
 * would stop telling the truth the first time a field was renamed.
 */
import { Badge, Banner, Button, DataTable, EmptyState, type Column } from '@coreintra/ui';
import { useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import { DateField } from './fields.js';
import { Money } from './money.js';
import {
  accounting,
  keys,
  type BalanceSheet,
  type CashFlow,
  type EquityStatement,
  type IncomeStatement,
  type PrepaidSchedules,
  type ReceivablesAgeing,
  type TrialBalance,
} from './queries.js';

export const REPORTS = [
  'trial-balance',
  'balance-sheet',
  'income-statement',
  'cash-flow',
  'equity-statement',
  'receivables-ageing',
  'prepaid-schedules',
] as const;

export type ReportName = (typeof REPORTS)[number];

const REPORT_LABEL: Readonly<Record<ReportName, string>> = {
  'trial-balance': 'trialBalance',
  'balance-sheet': 'balanceSheet',
  'income-statement': 'incomeStatement',
  'cash-flow': 'cashFlow',
  'equity-statement': 'equityStatement',
  'receivables-ageing': 'receivablesAgeing',
  'prepaid-schedules': 'prepaidSchedules',
};

/** The three that report a span rather than a moment. */
const PERIOD_REPORTS: ReadonlySet<ReportName> = new Set<ReportName>([
  'income-statement',
  'cash-flow',
  'equity-statement',
]);

/** The two sub-ledger views, driven by the client dimension on postings. */
const SUB_LEDGER: ReadonlySet<ReportName> = new Set<ReportName>(['receivables-ageing', 'prepaid-schedules']);

export interface Period {
  readonly asOf: string;
  readonly from: string;
  readonly to: string;
}

function today(): string {
  const now = new Date();
  return `${String(now.getFullYear())}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function startOfYear(): string {
  return `${String(new Date().getFullYear())}-01-01`;
}

function useReport<T>(report: ReportName, query: Readonly<Record<string, string | undefined>>): UseQueryResult<T> {
  const { bookId } = useBook();
  return useQuery({
    queryKey: keys.report(bookId ?? '', report, query),
    queryFn: () => accounting.report<T>(bookId ?? '', report, query),
    enabled: bookId !== null,
  });
}

/** Loading and failure, said once for every report. */
function ReportState({ query }: { readonly query: UseQueryResult<unknown> }): ReactNode {
  const { t } = useTranslation();
  if (query.isError) {
    return <Banner tone="danger">{presentError(query.error, t).message}</Banner>;
  }
  if (query.isPending) {
    return <p className="acc-note">{t('app.loading')}</p>;
  }
  return null;
}

export function ReportsScreen({ report }: { readonly report: ReportName }): ReactNode {
  const { t } = useTranslation();
  const { bookId } = useBook();
  const queryClient = useQueryClient();

  const [asOf, setAsOf] = useState(today);
  const [from, setFrom] = useState(startOfYear);
  const [to, setTo] = useState(today);

  const isPeriod = PERIOD_REPORTS.has(report);
  const period: Period = { asOf, from, to };

  if (bookId === null) {
    return <EmptyState message={t('ledger.book.none')} />;
  }

  return (
    <>
      <nav className="acc-tabs" aria-label={t('ledger.reports.title')}>
        {REPORTS.map((name) => (
          <Link
            key={name}
            to={`/accounting/reports/${name}`}
            className="acc-tab"
            aria-current={name === report ? 'page' : undefined}
          >
            {t(`ledger.reports.name.${REPORT_LABEL[name]}`)}
            {SUB_LEDGER.has(name) ? <span className="acc-muted"> · {t('ledger.reports.subLedger')}</span> : null}
          </Link>
        ))}
      </nav>

      <div className="acc-toolbar">
        {isPeriod ? (
          <>
            <DateField label={t('ledger.reports.from')} value={from} onChange={setFrom} />
            <DateField label={t('ledger.reports.to')} value={to} onChange={setTo} />
          </>
        ) : (
          <DateField label={t('ledger.asOf.label')} hint={t('ledger.asOf.hint')} value={asOf} onChange={setAsOf} />
        )}
        <Button
          onClick={() =>
            void queryClient.invalidateQueries({ queryKey: ['accounting', 'report', bookId, report] })
          }
        >
          {t('ledger.reports.run')}
        </Button>
      </div>

      <p className="acc-note">{t('ledger.reports.hint')}</p>

      <ReportBody report={report} period={period} />
    </>
  );
}

function ReportBody({ report, period }: { readonly report: ReportName; readonly period: Period }): ReactNode {
  switch (report) {
    case 'trial-balance':
      return <TrialBalanceView asOf={period.asOf} />;
    case 'balance-sheet':
      return <BalanceSheetView asOf={period.asOf} />;
    case 'income-statement':
      return <IncomeStatementView from={period.from} to={period.to} />;
    case 'cash-flow':
      return <CashFlowView from={period.from} to={period.to} />;
    case 'equity-statement':
      return <EquityStatementView from={period.from} to={period.to} />;
    case 'receivables-ageing':
      return <ReceivablesAgeingView asOf={period.asOf} />;
    case 'prepaid-schedules':
      return <PrepaidSchedulesView asOf={period.asOf} />;
    default:
      return null;
  }
}

/**
 * The integrity alarm.
 *
 * A `false` here means the ledger does not add up, which is a data problem and
 * never something for the UI to smooth over. It is loud, it carries the exact
 * difference the server computed, and it is announced: `<Banner tone="danger">`
 * renders with `role="alert"`.
 *
 * Anything other than an explicit `true` raises it, a missing flag included. A
 * statement that forgot to say whether it balances has not said that it does.
 */
function IntegrityFlag({
  balanced,
  imbalance,
}: {
  readonly balanced: boolean | undefined;
  readonly imbalance: string | undefined;
}): ReactNode {
  const { t } = useTranslation();
  if (balanced === true) {
    return <Badge tone="positive">{t('accounting.balancedFlag')}</Badge>;
  }
  return (
    <Banner tone="danger" title={t('ledger.reports.balancedAlarmTitle')}>
      <p>{t('ledger.reports.balancedAlarm', { imbalance: imbalance ?? '—' })}</p>
      <p>
        {t('ledger.reports.imbalance')}: <Money value={imbalance} withCurrency />
      </p>
    </Banner>
  );
}

function useAccountNames(): (accountId: string | undefined) => string {
  const { bookId } = useBook();
  const accountsQuery = useQuery({
    queryKey: keys.accounts(bookId ?? ''),
    queryFn: () => accounting.accounts(bookId ?? ''),
    enabled: bookId !== null,
  });
  const names = useMemo(() => {
    const map = new Map<string, string>();
    for (const account of accountsQuery.data ?? []) {
      if (account.id !== undefined) {
        map.set(account.id, account.nameKo ?? account.nameEn ?? account.id);
      }
    }
    return map;
  }, [accountsQuery.data]);
  return (accountId) => (accountId === undefined ? '' : (names.get(accountId) ?? accountId));
}

function Figures({ rows }: { readonly rows: readonly (readonly [string, string | undefined])[] }): ReactNode {
  return (
    <dl className="acc-figures">
      {rows.map(([label, value]) => (
        <div key={label} className="acc-figures__row">
          <dt>{label}</dt>
          <dd className="ci-numeric">
            <Money value={value} withCurrency />
          </dd>
        </div>
      ))}
    </dl>
  );
}

type TrialLine = NonNullable<TrialBalance['lines']>[number];

function TrialBalanceView({ asOf }: { readonly asOf: string }): ReactNode {
  const { t } = useTranslation();
  const nameOf = useAccountNames();
  const query = useReport<TrialBalance>('trial-balance', { asOf });
  const data = query.data;

  const columns: readonly Column<TrialLine>[] = [
    {
      key: 'account',
      header: t('ledger.reports.columnAccount'),
      render: (line) => (
        <>
          <span className="ci-numeric">{line.accountId}</span> {nameOf(line.accountId)}
        </>
      ),
    },
    { key: 'debit', header: t('ledger.reports.columnDebit'), numeric: true, render: (line) => <Money value={line.debit} /> },
    { key: 'credit', header: t('ledger.reports.columnCredit'), numeric: true, render: (line) => <Money value={line.credit} /> },
    { key: 'net', header: t('ledger.reports.columnNet'), numeric: true, render: (line) => <Money value={line.net} signed /> },
  ];

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  return (
    <>
      <IntegrityFlag balanced={data.balanced} imbalance={data.imbalance} />
      <DataTable
        caption={t('ledger.reports.name.trialBalance')}
        columns={columns}
        rows={data.lines ?? []}
        rowKey={(line) => line.accountId ?? ''}
        emptyMessage={t('ledger.reports.empty')}
      />
      <Figures
        rows={[
          [t('ledger.reports.totalDebits'), data.totalDebits],
          [t('ledger.reports.totalCredits'), data.totalCredits],
        ]}
      />
    </>
  );
}

function BalanceSheetView({ asOf }: { readonly asOf: string }): ReactNode {
  const { t } = useTranslation();
  const query = useReport<BalanceSheet>('balance-sheet', { asOf });
  const data = query.data;

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  const balanced = data.balanced === true;

  return (
    <>
      <IntegrityFlag balanced={data.balanced} imbalance={data.imbalance} />
      <div className={balanced ? 'acc-statement' : 'acc-statement acc-statement--suspect'}>
        <Figures
          rows={[
            [t('ledger.reports.assets'), data.assets],
            [t('ledger.reports.liabilities'), data.liabilities],
            [t('ledger.reports.equity'), data.equity],
            [t('accounting.unclosedNetIncome'), data.unclosedNetIncome],
          ]}
        />
      </div>
      <p className="acc-note">{t('ledger.reports.unclosedNetIncomeHint')}</p>
    </>
  );
}

function IncomeStatementView({ from, to }: { readonly from: string; readonly to: string }): ReactNode {
  const { t } = useTranslation();
  const query = useReport<IncomeStatement>('income-statement', { from, to });
  const data = query.data;

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  return (
    <>
      <Figures
        rows={[
          [t('ledger.reports.income'), data.income],
          [t('ledger.reports.expense'), data.expense],
          [t('ledger.reports.netIncome'), data.netIncome],
        ]}
      />
      <p className="acc-note">{t('ledger.batches.closingExcluded')}</p>
    </>
  );
}

function CashFlowView({ from, to }: { readonly from: string; readonly to: string }): ReactNode {
  const { t } = useTranslation();
  const query = useReport<CashFlow>('cash-flow', { from, to });
  const data = query.data;

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  const sections = Object.entries(data.sections ?? {});

  return (
    <>
      {data.reconciles === true ? (
        <Badge tone="positive">{t('ledger.reports.cashReconciles')}</Badge>
      ) : (
        <Banner tone="danger" title={t('ledger.reports.cashDiscrepancyTitle')}>
          <p>{t('ledger.reports.cashDiscrepancy', { discrepancy: data.discrepancy ?? '—' })}</p>
          <p>
            {t('ledger.reports.imbalance')}: <Money value={data.discrepancy} withCurrency />
          </p>
        </Banner>
      )}

      <Figures rows={[[t('ledger.reports.openingCash'), data.openingCash]]} />
      <h3 className="acc-section-title">{t('ledger.reports.sections')}</h3>
      <Figures
        rows={sections.map(
          ([name, value]) =>
            [t(`ledger.accounts.classificationValue.${name}`, { defaultValue: name }), value] as const,
        )}
      />
      <Figures
        rows={[
          [t('ledger.reports.netMovement'), data.netMovement],
          [t('ledger.reports.closingCash'), data.closingCash],
        ]}
      />
    </>
  );
}

function EquityStatementView({ from, to }: { readonly from: string; readonly to: string }): ReactNode {
  const { t } = useTranslation();
  const query = useReport<EquityStatement>('equity-statement', { from, to });
  const data = query.data;

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  return (
    <>
      <IntegrityFlag balanced={data.balanced} imbalance={data.imbalance} />
      <Figures
        rows={[
          [t('ledger.reports.openingEquity'), data.openingEquity],
          [t('ledger.reports.netIncome'), data.netIncome],
          [t('ledger.reports.ownerMovements'), data.ownerMovements],
          [t('ledger.reports.transferredToEquityByClosing'), data.transferredToEquityByClosing],
          [t('ledger.reports.closingAdjustments'), data.closingAdjustments],
          [t('ledger.reports.closingEquity'), data.closingEquity],
        ]}
      />
    </>
  );
}

type AgeingLine = NonNullable<ReceivablesAgeing['lines']>[number];

function ReceivablesAgeingView({ asOf }: { readonly asOf: string }): ReactNode {
  const { t } = useTranslation();
  const nameOf = useAccountNames();
  const query = useReport<ReceivablesAgeing>('receivables-ageing', { asOf });
  const data = query.data;
  const bands = data?.bandDays ?? [];

  const bucketHeaders = [
    ...bands.map((band, index) => {
      const previous = index === 0 ? 0 : (bands[index - 1] ?? 0) + 1;
      return t('ledger.reports.band', { from: previous, to: band });
    }),
    t('ledger.reports.bandOver', { from: bands[bands.length - 1] ?? 0 }),
  ];

  const columns: readonly Column<AgeingLine>[] = [
    { key: 'client', header: t('ledger.reports.client'), render: (line) => line.clientId ?? t('ledger.reports.clientUnassigned') },
    {
      key: 'account',
      header: t('ledger.reports.columnAccount'),
      render: (line) => (
        <>
          <span className="ci-numeric">{line.accountId}</span> {nameOf(line.accountId)}
        </>
      ),
    },
    ...bucketHeaders.map((header, index) => ({
      key: `bucket-${String(index)}`,
      header,
      numeric: true,
      render: (line: AgeingLine) => <Money value={(line.buckets ?? [])[index]} />,
    })),
    {
      key: 'outstanding',
      header: t('ledger.reports.outstanding'),
      numeric: true,
      render: (line) => <Money value={line.outstanding} />,
    },
    {
      key: 'unapplied',
      header: t('ledger.reports.unappliedCredits'),
      numeric: true,
      render: (line) => <Money value={line.unappliedCredits} />,
    },
    {
      key: 'oldest',
      header: t('ledger.reports.oldestItem'),
      numeric: true,
      render: (line) =>
        line.oldestItemDays === undefined ? '' : t('ledger.reports.days', { count: line.oldestItemDays }),
    },
  ];

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  return (
    <>
      <DataTable
        caption={t('ledger.reports.name.receivablesAgeing')}
        columns={columns}
        rows={data.lines ?? []}
        rowKey={(line) => `${line.clientId ?? '—'}:${line.accountId ?? ''}`}
        emptyMessage={t('ledger.reports.empty')}
      />
      <Figures
        rows={[
          [t('ledger.reports.total'), data.total],
          [t('ledger.reports.unappliedCredits'), data.totalUnappliedCredits],
          [t('ledger.reports.clientUnassigned'), data.unassigned],
        ]}
      />
    </>
  );
}

type PrepaidLine = NonNullable<PrepaidSchedules['lines']>[number];

function PrepaidSchedulesView({ asOf }: { readonly asOf: string }): ReactNode {
  const { t } = useTranslation();
  const nameOf = useAccountNames();
  const query = useReport<PrepaidSchedules>('prepaid-schedules', { asOf });
  const data = query.data;

  const columns: readonly Column<PrepaidLine>[] = [
    {
      key: 'account',
      header: t('ledger.reports.columnAccount'),
      render: (line) => (
        <>
          <span className="ci-numeric">{line.accountId}</span> {nameOf(line.accountId)}
        </>
      ),
    },
    { key: 'client', header: t('ledger.reports.client'), render: (line) => line.clientId ?? t('ledger.reports.clientUnassigned') },
    { key: 'capitalised', header: t('ledger.reports.capitalised'), numeric: true, render: (line) => <Money value={line.capitalised} /> },
    { key: 'scheduled', header: t('ledger.reports.scheduled'), numeric: true, render: (line) => <Money value={line.scheduledTotal} /> },
    { key: 'unexpired', header: t('ledger.reports.unexpired'), numeric: true, render: (line) => <Money value={line.unexpired} /> },
    {
      key: 'through',
      header: t('ledger.reports.scheduledThrough', { date: '' }),
      render: (line) => line.scheduledThrough ?? '',
    },
    {
      key: 'complete',
      header: t('common.status'),
      render: (line) =>
        line.fullyScheduled === true ? (
          <Badge tone="positive">{t('ledger.reports.fullyScheduled')}</Badge>
        ) : (
          <Badge tone="warning">{t('ledger.reports.partiallyScheduled')}</Badge>
        ),
    },
  ];

  if (data === undefined) {
    return <ReportState query={query} />;
  }

  return (
    <>
      <DataTable
        caption={t('ledger.reports.name.prepaidSchedules')}
        columns={columns}
        rows={data.lines ?? []}
        rowKey={(line) => `${line.accountId ?? ''}:${line.clientId ?? '—'}`}
        emptyMessage={t('ledger.reports.empty')}
      />
      <Figures
        rows={[
          [t('ledger.reports.scheduled'), data.totalScheduled],
          [t('ledger.reports.unexpired'), data.totalUnexpired],
          [t('ledger.reports.unscheduled'), data.unscheduled],
        ]}
      />
    </>
  );
}
