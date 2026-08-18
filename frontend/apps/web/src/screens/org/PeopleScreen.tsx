import type { components } from '@coreintra/api-client';
import { Badge, Banner, Button, DataTable, EmptyState, type Column } from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useMemo, useState, type ReactNode } from 'react';
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

type EmployeeView = components['schemas']['EmployeeView'];
type PositionView = components['schemas']['PositionView'];
type OrgUnitView = components['schemas']['OrgUnitView'];

/**
 * People, and where each of them sat.
 *
 * Position history is the answer to "who did this person report to when they
 * signed that": §3 keeps the whole history rather than overwriting it, so the
 * screen shows the rows with their own from/to dates rather than only the one
 * in force on the header date.
 */
export function PeopleScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const language = i18n.language;
  const [asOf, setAsOf] = useState(todayBusinessDate);
  const [includeLeavers, setIncludeLeavers] = useState(false);
  const [employeeId, setEmployeeId] = useState<string | null>(null);
  const choice = useCompanyChoice(asOf);
  const companyId = choice.companyId;
  const onRowKeyDown = useRowArrowKeys();

  const employees = useQuery({
    queryKey: ['org', 'employees', companyId, asOf, includeLeavers],
    enabled: companyId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPageEmployeeView']>('/org/employees', {
        query: { companyId: companyId ?? '', businessDate: asOf, includeLeavers, limit: 500 },
      });
      return page.items ?? [];
    },
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

  const history = useQuery({
    queryKey: ['org', 'positions', employeeId, asOf],
    enabled: employeeId !== null,
    queryFn: async () => {
      const page = await api.get<components['schemas']['CursorPagePositionView']>(
        `/org/employees/${encodeURIComponent(employeeId ?? '')}/positions`,
        { query: { businessDate: asOf, limit: 200 } },
      );
      return page.items ?? [];
    },
  });

  const unitById = useMemo(() => {
    const map: Record<string, OrgUnitView> = {};
    for (const unit of units.data ?? []) {
      if (unit.id !== undefined) {
        map[unit.id] = unit;
      }
    }
    return map;
  }, [units.data]);

  const selected = (employees.data ?? []).find((employee) => employee.id === employeeId);
  const failure = choice.error ?? employees.error ?? history.error;

  const columns: readonly Column<EmployeeView>[] = [
    {
      key: 'name',
      header: t('org.colName'),
      render: (employee) => localisedName(employee, language),
    },
    {
      key: 'number',
      header: t('org.colNumber'),
      numeric: true,
      render: (employee) => employee.employeeNumber ?? '-',
    },
    { key: 'email', header: t('org.colEmail'), render: (employee) => employee.email ?? '-' },
    {
      key: 'hired',
      header: t('org.colHired'),
      numeric: true,
      render: (employee) => employee.hiredOn ?? '-',
    },
    {
      key: 'terminated',
      header: t('org.colTerminated'),
      numeric: true,
      render: (employee) =>
        employee.terminatedOn === undefined ? (
          <Badge tone="positive">{t('org.open')}</Badge>
        ) : (
          employee.terminatedOn
        ),
    },
    {
      key: 'explain',
      header: t('common.action'),
      render: (employee) =>
        employee.id === undefined ? null : (
          <Link
            to="/org/explainer"
            search={{ employeeId: employee.id, asOf }}
            className="ci-button ci-button--support"
          >
            {t('org.explainThisPerson')}
          </Link>
        ),
    },
  ];

  const historyColumns: readonly Column<PositionView>[] = [
    {
      key: 'unit',
      header: t('org.colUnit'),
      render: (position) => {
        const unit = position.orgUnitId === undefined ? undefined : unitById[position.orgUnitId];
        return unit === undefined ? (position.orgUnitId ?? '-') : localisedName(unit, language);
      },
    },
    {
      key: 'primary',
      header: t('org.colPrimary'),
      render: (position) =>
        position.primary === true ? t('org.primaryYes') : t('org.primaryNo'),
    },
    { key: 'from', header: t('org.colFrom'), numeric: true, render: (position) => position.effectiveFrom ?? '-' },
    {
      key: 'to',
      header: t('org.colTo'),
      numeric: true,
      render: (position) => position.effectiveTo ?? t('org.open'),
    },
  ];

  return (
    <Page
      title={t('org.employees')}
      actions={
        <Link to="/org" className="ci-button">
          {t('org.title')}
        </Link>
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
          <label className="ci-field" style={{ flexDirection: 'row', alignItems: 'center', gap: 'var(--ci-space-2)' }}>
            <input
              type="checkbox"
              checked={includeLeavers}
              onChange={(event) => {
                setIncludeLeavers(event.target.checked);
              }}
            />
            <span>{t('org.includeLeavers')}</span>
          </label>
        </div>
      </div>

      {failure === null || failure === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(failure, t).message}
        </Banner>
      )}

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <div onKeyDown={onRowKeyDown}>
          <DataTable
            caption={t('org.employees')}
            columns={columns}
            rows={employees.data ?? []}
            rowKey={(employee) => employee.id ?? ''}
            emptyMessage={t('org.employeesEmpty')}
            onRowActivate={(employee) => {
              setEmployeeId(employee.id ?? null);
            }}
          />
        </div>
      </section>

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>
          {selected === undefined
            ? t('org.positionHistory')
            : `${t('org.positionHistory')} — ${localisedName(selected, language)}`}
        </h2>
        {employeeId === null ? (
          <EmptyState message={t('org.positionHistoryEmpty')} />
        ) : (
          <DataTable
            caption={t('org.positionHistory')}
            columns={historyColumns}
            rows={history.data ?? []}
            rowKey={(position) => position.id ?? ''}
            emptyMessage={t('org.positionHistoryEmpty')}
          />
        )}
      </section>
    </Page>
  );
}
