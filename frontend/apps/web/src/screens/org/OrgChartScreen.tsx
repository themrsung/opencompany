import type { components } from '@coreintra/api-client';
import { Badge, Banner, Button, DataTable, EmptyState, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import { useRowArrowKeys } from '../../session/keyboard.js';
import {
  AsOfField,
  CompanySelect,
  localisedName,
  todayBusinessDate,
  useCompanyChoice,
} from '../../session/viewingContext.js';

type OrgUnitView = components['schemas']['OrgUnitView'];
type PositionView = components['schemas']['PositionView'];
type RankView = components['schemas']['RankView'];
type JobFunctionView = components['schemas']['JobFunctionView'];
type CompanyView = components['schemas']['CompanyView'];

/**
 * The org chart on a date.
 *
 * §3 makes every question here a question about a business date: the tree, the
 * roster, the ranks and who was where all resolve against the date in the
 * header rather than against today. So the date is a control at the top of the
 * screen and not a filter hidden in a panel — reading this screen without
 * knowing which day you are looking at is reading it wrong.
 */
export function OrgChartScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const language = i18n.language;
  const [asOf, setAsOf] = useState(todayBusinessDate);
  const choice = useCompanyChoice(asOf);
  const companyId = choice.companyId;
  const [unitId, setUnitId] = useState<string | null>(null);
  const [includeRetired, setIncludeRetired] = useState(false);
  // A pending ladder, most senior first. Null while nobody has moved anything:
  // the saved order is the server's, and this only shadows it once a person
  // starts rearranging.
  const [ladder, setLadder] = useState<readonly string[] | null>(null);
  const queryClient = useQueryClient();
  const onRowKeyDown = useRowArrowKeys();

  const company = useQuery({
    queryKey: ['org', 'company', companyId, asOf],
    enabled: companyId !== null,
    queryFn: async () =>
      api.get<CompanyView>(`/org/companies/${encodeURIComponent(companyId ?? '')}`, {
        query: { businessDate: asOf },
      }),
  });

  const units = useQuery({
    queryKey: ['org', 'units', companyId, asOf],
    enabled: companyId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPageOrgUnitView']>('/org/units', {
        query: { companyId: companyId ?? '', businessDate: asOf, limit: 500 },
      });
      return page.items ?? [];
    },
  });

  const ranks = useQuery({
    queryKey: ['org', 'ranks', companyId, asOf],
    enabled: companyId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPageRankView']>(
        `/org/companies/${encodeURIComponent(companyId ?? '')}/ranks`,
        { query: { businessDate: asOf, limit: 200 } },
      );
      return page.items ?? [];
    },
  });

  const jobFunctions = useQuery({
    queryKey: ['org', 'job-functions', companyId, asOf, includeRetired],
    enabled: companyId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPageJobFunctionView']>(
        `/org/companies/${encodeURIComponent(companyId ?? '')}/job-functions`,
        { query: { businessDate: asOf, limit: 200, includeRetired } },
      );
      return page.items ?? [];
    },
  });

  const roster = useQuery({
    queryKey: ['org', 'roster', unitId, asOf],
    enabled: unitId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPagePositionView']>(
        `/org/units/${encodeURIComponent(unitId ?? '')}/positions`,
        { query: { businessDate: asOf, limit: 200 } },
      );
      return page.items ?? [];
    },
  });

  // Positions carry ids, not names. One list of employees for the whole screen
  // is cheaper than a request per row and keeps the roster from arriving in
  // pieces.
  const employees = useQuery({
    queryKey: ['org', 'employees', companyId, asOf, true],
    enabled: companyId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPageEmployeeView']>('/org/employees', {
        query: {
          companyId: companyId ?? '',
          businessDate: asOf,
          includeLeavers: true,
          limit: 500,
        },
      });
      return page.items ?? [];
    },
  });

  /**
   * Reordering is one call over the whole ladder — `rankIdsMostSeniorFirst` —
   * because that is the only way the server can honour it atomically. So the
   * screen never sends a single row's seniority: moving a rung rearranges a
   * local list, and Save writes the entire ladder in one request.
   */
  const reorder = useMutation({
    mutationFn: (rankIdsMostSeniorFirst: readonly string[]) =>
      api.request(`/org/companies/${encodeURIComponent(companyId ?? '')}/ranks/order`, {
        method: 'PUT',
        body: { rankIdsMostSeniorFirst: [...rankIdsMostSeniorFirst] },
        query: { businessDate: asOf },
      }),
    onSuccess: () => {
      setLadder(null);
      void queryClient.invalidateQueries({ queryKey: ['org', 'ranks'] });
    },
  });

  const unitRows = useMemo(() => sortUnits(units.data ?? []), [units.data]);
  const employeeById = useMemo(() => byId(employees.data ?? []), [employees.data]);
  const rankById = useMemo(() => byId(ranks.data ?? []), [ranks.data]);
  const selected = unitRows.find((unit) => unit.id === unitId);

  const savedLadder = useMemo(
    () => [...(ranks.data ?? [])].sort(mostSeniorFirst),
    [ranks.data],
  );
  const shownLadder = useMemo(() => {
    if (ladder === null) {
      return savedLadder;
    }
    const known = byId(savedLadder);
    return ladder.flatMap((id) => {
      const rank = known[id];
      return rank === undefined ? [] : [rank];
    });
  }, [ladder, savedLadder]);

  const move = useCallback(
    (rankId: string, by: -1 | 1) => {
      const order = shownLadder.map((rank) => rank.id ?? '');
      const from = order.indexOf(rankId);
      const to = from + by;
      const moving = order[from];
      const displaced = order[to];
      if (from === -1 || moving === undefined || displaced === undefined) {
        return;
      }
      const next = [...order];
      next[from] = displaced;
      next[to] = moving;
      setLadder(next);
    },
    [shownLadder],
  );

  const failure =
    choice.error ?? units.error ?? ranks.error ?? jobFunctions.error ?? reorder.error;

  const unitColumns: readonly Column<OrgUnitView>[] = [
    {
      key: 'name',
      header: t('common.orgUnit'),
      render: (unit) => (
        <span style={{ paddingLeft: `${String((unit.depth ?? 0) * 12)}px` }}>
          {localisedName(unit, language)}
        </span>
      ),
    },
    { key: 'code', header: t('org.colCode'), render: (unit) => unit.code ?? '-' },
  ];

  const rosterColumns: readonly Column<PositionView>[] = [
    {
      key: 'name',
      header: t('org.colName'),
      render: (position) => {
        const employee = position.employeeId === undefined ? undefined : employeeById[position.employeeId];
        return employee === undefined ? (position.employeeId ?? '-') : localisedName(employee, language);
      },
    },
    {
      key: 'rank',
      header: t('org.colRank'),
      render: (position) => {
        const rank = position.rankId === undefined ? undefined : rankById[position.rankId];
        return rank === undefined ? '-' : rankLabel(rank, language);
      },
    },
    {
      key: 'primary',
      header: t('org.colPrimary'),
      render: (position) =>
        position.primary === true ? (
          <Badge tone="accent">{t('org.primaryYes')}</Badge>
        ) : (
          <Badge>{t('org.primaryNo')}</Badge>
        ),
    },
    { key: 'from', header: t('org.colFrom'), render: (position) => position.effectiveFrom ?? '-' },
    {
      key: 'to',
      header: t('org.colTo'),
      render: (position) => position.effectiveTo ?? t('org.open'),
    },
    {
      key: 'explain',
      header: t('common.action'),
      render: (position) =>
        position.employeeId === undefined ? null : (
          <Link
            to="/org/explainer"
            search={{ employeeId: position.employeeId, asOf }}
            className="ci-button ci-button--support"
          >
            {t('org.explainThisPerson')}
          </Link>
        ),
    },
  ];

  const rankColumns: readonly Column<RankView>[] = [
    { key: 'label', header: t('common.rank'), render: (rank) => rankLabel(rank, language) },
    { key: 'code', header: t('org.colCode'), render: (rank) => rank.code ?? '-' },
    {
      key: 'seniority',
      header: t('org.colSeniority'),
      numeric: true,
      // Read-only, deliberately: the number is the server's, and the way to
      // change it is to move the rung and save the ladder.
      render: (rank) => (rank.seniority === undefined ? '-' : String(rank.seniority)),
    },
    {
      key: 'representative',
      header: t('org.colRepresentative'),
      render: (rank) =>
        rank.representative === true ? <Badge tone="accent">{t('org.representativeYes')}</Badge> : null,
    },
    {
      key: 'move',
      header: t('common.action'),
      render: (rank) => {
        const at = shownLadder.findIndex((one) => one.id === rank.id);
        return (
          <span style={{ display: 'flex', gap: 'var(--ci-space-2)' }}>
            <Button
              disabled={at <= 0}
              onClick={() => {
                move(rank.id ?? '', -1);
              }}
            >
              {t('org.moveUp')}
            </Button>
            <Button
              disabled={at === -1 || at >= shownLadder.length - 1}
              onClick={() => {
                move(rank.id ?? '', 1);
              }}
            >
              {t('org.moveDown')}
            </Button>
          </span>
        );
      },
    },
  ];

  const jobFunctionColumns: readonly Column<JobFunctionView>[] = [
    {
      key: 'label',
      header: t('common.jobFunction'),
      render: (jobFunction) => rankLabel(jobFunction, language),
    },
    { key: 'code', header: t('org.colCode'), render: (jobFunction) => jobFunction.code ?? '-' },
  ];

  return (
    <Page
      title={t('org.title')}
      actions={
        <>
          <Link to="/org/people" className="ci-button">
            {t('org.employees')}
          </Link>
          <Link to="/org/explainer" className="ci-button">
            {t('explainer.title')}
          </Link>
        </>
      }
    >
      <div className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
        <div
          style={{
            display: 'flex',
            gap: 'var(--ci-space-4)',
            alignItems: 'flex-end',
            flexWrap: 'wrap',
          }}
        >
          <CompanySelect choice={choice} language={language} />
          <AsOfField value={asOf} onChange={setAsOf} label={t('org.asOf')} hint={t('org.asOfHint')} />
          <Button
            onClick={() => {
              setAsOf(todayBusinessDate());
            }}
          >
            {t('org.asOfToday')}
          </Button>
        </div>
        {choice.loading || choice.companies.length > 0 ? null : (
          <p style={{ color: 'var(--ci-fg-muted)' }}>{t('org.companyEmpty')}</p>
        )}
        {company.data === undefined ? null : <Registration company={company.data} />}
      </div>

      {failure === null || failure === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(failure, t).message}
        </Banner>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(16rem, 22rem) 1fr',
          gap: 'var(--ci-space-4)',
          marginTop: 'var(--ci-space-4)',
          alignItems: 'start',
        }}
      >
        <section className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
          <h2>{t('org.units')}</h2>
          <div onKeyDown={onRowKeyDown}>
            <DataTable
              caption={t('org.units')}
              columns={unitColumns}
              rows={unitRows}
              rowKey={(unit) => unit.id ?? ''}
              emptyMessage={t('org.unitsEmpty')}
              onRowActivate={(unit) => {
                setUnitId(unit.id ?? null);
              }}
            />
          </div>
        </section>

        <section className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
          <h2>
            {selected === undefined
              ? t('org.roster')
              : t('org.rosterOf', { unit: localisedName(selected, language) })}
          </h2>
          {unitId === null ? (
            <EmptyState message={t('org.selectUnit')} />
          ) : (
            <div onKeyDown={onRowKeyDown}>
              <DataTable
                caption={t('org.roster')}
                columns={rosterColumns}
                rows={roster.data ?? []}
                rowKey={(position) => position.id ?? ''}
                emptyMessage={t('org.rosterEmpty')}
              />
            </div>
          )}
        </section>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 'var(--ci-space-4)',
          marginTop: 'var(--ci-space-4)',
          alignItems: 'start',
        }}
      >
        <section className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
          <h2>{t('org.ranks')}</h2>
          <p style={{ color: 'var(--ci-fg-muted)' }}>{t('org.ranksOrderHint')}</p>
          <p style={{ color: 'var(--ci-fg-muted)' }}>{t('org.orderAtomic')}</p>
          <DataTable
            caption={t('org.ranks')}
            columns={rankColumns}
            rows={shownLadder}
            rowKey={(rank) => rank.id ?? ''}
            emptyMessage={t('org.ranksEmpty')}
          />
          <div style={{ display: 'flex', gap: 'var(--ci-space-3)', marginTop: 'var(--ci-space-3)' }}>
            <Button
              tone="primary"
              disabled={ladder === null}
              busy={reorder.isPending}
              onClick={() => {
                reorder.mutate(shownLadder.map((rank) => rank.id ?? ''));
              }}
            >
              {t('org.saveOrder')}
            </Button>
            <Button
              disabled={ladder === null}
              onClick={() => {
                setLadder(null);
              }}
            >
              {t('org.discardOrder')}
            </Button>
            {reorder.isSuccess && ladder === null ? <Badge tone="positive">{t('org.orderSaved')}</Badge> : null}
          </div>
        </section>
        <section className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
          <h2>{t('org.jobFunctions')}</h2>
          <p style={{ color: 'var(--ci-fg-muted)' }}>{t('org.jobFunctionsHint')}</p>
          <label
            className="ci-field"
            style={{ flexDirection: 'row', alignItems: 'center', gap: 'var(--ci-space-2)' }}
          >
            <input
              type="checkbox"
              checked={includeRetired}
              onChange={(event) => {
                setIncludeRetired(event.target.checked);
              }}
            />
            <span>{t('org.includeRetired')}</span>
          </label>
          <DataTable
            caption={t('org.jobFunctions')}
            columns={jobFunctionColumns}
            rows={jobFunctions.data ?? []}
            rowKey={(jobFunction) => jobFunction.id ?? ''}
            emptyMessage={t('org.jobFunctionsEmpty')}
          />
        </section>
      </div>
    </Page>
  );
}

function Registration({ company }: { readonly company: CompanyView }): ReactNode {
  const { t } = useTranslation();
  return (
    <>
      <h2 style={{ margin: 'var(--ci-space-4) 0 0' }}>{t('org.registration')}</h2>
      <dl
        style={{
          display: 'flex',
          gap: 'var(--ci-space-5)',
          margin: 'var(--ci-space-2) 0 0',
          flexWrap: 'wrap',
        }}
      >
        <Fact
          label={t('org.registrationNumber')}
          value={company.businessRegistrationNumber}
          numeric
        />
        <Fact label={t('org.establishedOn')} value={company.establishedOn} numeric />
        <Fact label={t('org.baseCurrency')} value={company.baseCurrencyCode} />
      </dl>
    </>
  );
}

function Fact({
  label,
  value,
  numeric = false,
}: {
  readonly label: string;
  readonly value: string | undefined;
  readonly numeric?: boolean;
}): ReactNode {
  return (
    <div>
      <dt style={{ color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>{label}</dt>
      <dd style={{ margin: 0 }} className={numeric ? 'ci-numeric' : undefined}>
        {value ?? '-'}
      </dd>
    </div>
  );
}

/** Depth-first by `path`, which the server builds so that string order is tree order. */
function sortUnits(units: readonly OrgUnitView[]): OrgUnitView[] {
  return [...units].sort((left, right) => (left.path ?? '').localeCompare(right.path ?? ''));
}

/** Most senior first, matching `rankIdsMostSeniorFirst` and the copy above the table. */
function mostSeniorFirst(left: RankView, right: RankView): number {
  return (right.seniority ?? 0) - (left.seniority ?? 0);
}

function byId<T extends { id?: string }>(rows: readonly T[]): Record<string, T> {
  const map: Record<string, T> = {};
  for (const row of rows) {
    if (row.id !== undefined) {
      map[row.id] = row;
    }
  }
  return map;
}

function rankLabel(
  row: { readonly labelKo?: string; readonly labelEn?: string },
  language: string,
): string {
  const korean = language.startsWith('ko');
  const first = korean ? row.labelKo : row.labelEn;
  const second = korean ? row.labelEn : row.labelKo;
  return first ?? second ?? '-';
}
