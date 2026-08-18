import { isOutsideCalendarDay } from '@coreintra/business-time';
import {
  Badge,
  Banner,
  BusinessInstantText,
  Button,
  DataTable,
  formatClockFace,
  type Column,
} from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import type { TFunction } from 'i18next';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import {
  attendance,
  attendanceKeys,
  crossesMidnight,
  nameIndex,
  readInstant,
  safeColour,
  todayBusinessDate,
  type AttendanceRecord,
  type StatusType,
  type WhoIsInEntry,
} from './queries.js';
import { useCompanyChoice } from './useCompanyChoice.js';
import './attendance.css';

/**
 * 근무 현황 — the other screen §12 says decides whether people like this
 * product.
 *
 * Two behaviours here are the product rather than the presentation.
 *
 * **비공개 is a row, not a hole.** A colleague whose status is not
 * `visibleToPeers` still appears, with their status withheld. Dropping them
 * would answer the question anyway — everyone can see who is missing from a
 * team list — so the row stays and says plainly that this is private.
 *
 * **27:00 is a normal time.** A shift that ran to three in the morning belongs
 * to the business day it began, and that is what the board shows, through
 * `<BusinessInstantText>`. Most people meet the 72-hour business day here for
 * the first time, so the value is marked as deliberate rather than flagged as
 * wrong, and the sentence explaining it is one hover or one row-open away.
 */
export function WhosInScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const { companies, companyId, choose } = useCompanyChoice();
  const [businessDate, setBusinessDate] = useState(todayBusinessDate);
  const [unitId, setUnitId] = useState<string | null>(null);
  const [openPerson, setOpenPerson] = useState<string | null>(null);

  const ready = companyId !== null;
  const company = companyId ?? '';

  const board = useQuery({
    queryKey: attendanceKeys.whosIn(company, businessDate, unitId),
    queryFn: () => attendance.whosIn(company, businessDate, unitId),
    enabled: ready,
  });
  const roster = useQuery({
    queryKey: attendanceKeys.employees(company),
    queryFn: () => attendance.employees(company),
    enabled: ready,
    staleTime: 5 * 60_000,
  });
  const statuses = useQuery({
    queryKey: attendanceKeys.statusTypes(company),
    queryFn: () => attendance.statusTypes(company),
    enabled: ready,
    staleTime: 5 * 60_000,
  });
  const units = useQuery({
    queryKey: attendanceKeys.units(company),
    queryFn: () => attendance.units(company),
    enabled: ready,
    staleTime: 5 * 60_000,
  });

  const names = useMemo(
    () => nameIndex(roster.data ?? [], i18n.language),
    [roster.data, i18n.language],
  );
  const entries = board.data ?? [];
  const working = entries.filter(
    (entry) => entry.current === true && entry.countsAsWorking === true,
  ).length;

  const nameOf = (employeeId: string | undefined): string =>
    (employeeId === undefined ? undefined : names.get(employeeId)) ?? employeeId ?? '—';

  return (
    <Page
      title={t('attendance.whosIn')}
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
          <span className="ci-field__label">{t('businessTime.businessDate')}</span>
          <input
            type="date"
            className="ci-field__input ci-numeric"
            value={businessDate}
            onChange={(event) => {
              setBusinessDate(event.target.value);
              setOpenPerson(null);
            }}
          />
        </label>

        <label className="ci-field whosin-control">
          <span className="ci-field__label">{t('whosin.unitLabel')}</span>
          <select
            className="ci-field__input"
            value={unitId ?? ''}
            onChange={(event) => {
              setUnitId(event.target.value === '' ? null : event.target.value);
              setOpenPerson(null);
            }}
          >
            <option value="">{t('whosin.allUnits')}</option>
            {(units.data ?? []).map((unit) => (
              <option key={unit.id} value={unit.id}>
                {i18n.language.startsWith('en')
                  ? (unit.nameEn ?? unit.nameKo ?? unit.id)
                  : (unit.nameKo ?? unit.nameEn ?? unit.id)}
              </option>
            ))}
          </select>
        </label>

        <p className="whosin-summary">
          {t('whosin.summary', { total: entries.length, working })}
        </p>
      </div>

      {board.isError ? (
        <Banner
          tone="danger"
          title={presentError(board.error, t).message}
          actions={
            <Button
              onClick={() => {
                void board.refetch();
              }}
            >
              {t('action.retry')}
            </Button>
          }
        >
          {t('error.unexpected')}
        </Banner>
      ) : null}

      <div className="page__surface">
        {board.isPending ? (
          // "Nobody to show" is an answer, and it must not be given before the
          // board has one.
          <p className="att-muted whosin-loading" role="status">
            {t('app.loading')}
          </p>
        ) : (
          <DataTable<WhoIsInEntry>
            caption={`${t('attendance.whosIn')} — ${businessDate} · ${t('whosin.openDay')}`}
            emptyMessage={t('whosin.empty')}
            rows={entries}
            rowKey={(entry) => entry.employeeId ?? String(entries.indexOf(entry))}
            onRowActivate={(entry) => {
              setOpenPerson(entry.employeeId ?? null);
            }}
            columns={boardColumns(t, nameOf)}
          />
        )}
      </div>

      {openPerson === null ? null : (
        <DayPanel
          companyId={company}
          employeeId={openPerson}
          name={nameOf(openPerson)}
          businessDate={businessDate}
          statuses={statuses.data ?? []}
          onClose={() => {
            setOpenPerson(null);
          }}
        />
      )}

      <Legend statuses={statuses.data ?? []} />
    </Page>
  );
}

/**
 * The board's columns.
 *
 * Built by a plain function rather than inline, so the cell renderers are
 * defined once at module scope instead of on every keystroke in the date field.
 */
function boardColumns(
  t: TFunction,
  nameOf: (employeeId: string | undefined) => string,
): Array<Column<WhoIsInEntry>> {
  return [
    {
      key: 'person',
      header: t('whosin.columnPerson'),
      render: (entry) => nameOf(entry.employeeId),
    },
    {
      key: 'status',
      header: t('whosin.columnStatus'),
      render: (entry) => <StatusCell entry={entry} />,
    },
    {
      key: 'started',
      header: t('whosin.columnStarted'),
      numeric: true,
      render: (entry) =>
        entry.visible === false ? <Withheld /> : <Moment wire={entry.startedAt} />,
    },
    {
      key: 'ended',
      header: t('whosin.columnEnded'),
      numeric: true,
      render: (entry) => {
        if (entry.visible === false) {
          return <Withheld />;
        }
        if (entry.endedAt === undefined) {
          return (
            <span className="att-muted">{entry.current === true ? t('whosin.stillOpen') : '—'}</span>
          );
        }
        return <Moment wire={entry.endedAt} businessDate={entry.businessDate} />;
      },
    },
  ];
}

/**
 * One person's day, in columns.
 *
 * `statusOf` is passed in rather than looked up here because the status types
 * are already loaded for the board and the legend; asking for them a third time
 * would be three requests for one list.
 */
function dayColumns(
  t: TFunction,
  statusOf: (statusTypeId: string | undefined) => string,
): Array<Column<AttendanceRecord>> {
  return [
    {
      key: 'status',
      header: t('whosin.columnStatus'),
      render: (record) => statusOf(record.statusTypeId),
    },
    {
      key: 'started',
      header: t('whosin.columnStarted'),
      numeric: true,
      render: (record) => <Moment wire={record.startedAt} />,
    },
    {
      key: 'ended',
      header: t('whosin.columnEnded'),
      numeric: true,
      render: (record) =>
        record.endedAt === undefined ? (
          <span className="att-muted">{t('whosin.stillOpen')}</span>
        ) : (
          <Moment wire={record.endedAt} businessDate={record.businessDate} />
        ),
    },
    {
      key: 'duration',
      header: t('whosin.columnDuration'),
      numeric: true,
      render: (record) =>
        record.durationSeconds === undefined
          ? '—'
          : t('whosin.duration', {
              hours: Math.floor(record.durationSeconds / 3600),
              minutes: Math.floor((record.durationSeconds % 3600) / 60),
            }),
    },
    {
      key: 'note',
      header: t('whosin.columnNote'),
      render: (record) => record.note ?? '—',
    },
    {
      // A status that needs 결재 first points at the document that gave it.
      key: 'source',
      header: t('whosin.approvedBy'),
      render: (record) =>
        record.sourceDocumentId === undefined ? (
          <span className="att-muted">—</span>
        ) : (
          <Link to={`/approvals/${record.sourceDocumentId}`} className="att-link">
            {record.sourceDocumentId}
          </Link>
        ),
    },
  ];
}

/**
 * A moment, and — when it left the calendar day — the sentence that explains it.
 *
 * `<BusinessInstantText>` already marks the value as deliberate. The title adds
 * the words, because "27:00" is only obvious to somebody who has met it before.
 */
function Moment({
  wire,
  businessDate,
}: {
  readonly wire: string | undefined;
  readonly businessDate?: string | undefined;
}): ReactNode {
  const { t } = useTranslation();
  const instant = readInstant(wire);

  if (instant === null) {
    return wire === undefined ? (
      <span className="att-muted">—</span>
    ) : (
      <span className="ci-numeric" title={t('businessTime.invalid')}>
        {wire}
      </span>
    );
  }

  if (!isOutsideCalendarDay(instant)) {
    return <BusinessInstantText value={instant} />;
  }

  return (
    <span
      title={t('attendance.crossesMidnight', {
        date: businessDate ?? instant.businessDate,
        clock: formatClockFace(instant.offsetSeconds),
      })}
    >
      <BusinessInstantText value={instant} />
    </span>
  );
}

function Withheld(): ReactNode {
  return <span className="att-muted">·</span>;
}

/**
 * What a peer may see of somebody's status.
 *
 * Three cases, and none of them is a blank cell: a status they share, a status
 * they do not, and no record at all. The second and third look different on
 * purpose — "비공개" and "기록 없음" are different facts, and conflating them
 * would let a reader infer the one that was meant to be private.
 */
function StatusCell({ entry }: { readonly entry: WhoIsInEntry }): ReactNode {
  const { t, i18n } = useTranslation();

  if (entry.visible === false) {
    return (
      <span title={t('whosin.privateHint')}>
        <Badge>{t('whosin.private')}</Badge>
      </span>
    );
  }

  if (entry.statusCode === undefined) {
    return (
      <span className="att-muted" title={t('whosin.noRecordHint')}>
        {t('whosin.noRecord')}
      </span>
    );
  }

  const label = i18n.language.startsWith('en')
    ? (entry.labelEn ?? entry.labelKo ?? entry.statusCode)
    : (entry.labelKo ?? entry.labelEn ?? entry.statusCode);
  const colour = safeColour(entry.colour);

  return (
    <span
      className="whosin-chip"
      style={colour === null ? undefined : { borderColor: colour, color: colour }}
    >
      {entry.icon === undefined ? null : (
        <span className="whosin-chip__icon" aria-hidden="true">
          {entry.icon}
        </span>
      )}
      {label}
    </span>
  );
}

/** One person's business day, in order, including the spell that ran past midnight. */
function DayPanel({
  companyId,
  employeeId,
  name,
  businessDate,
  statuses,
  onClose,
}: {
  readonly companyId: string;
  readonly employeeId: string;
  readonly name: string;
  readonly businessDate: string;
  readonly statuses: readonly StatusType[];
  readonly onClose: () => void;
}): ReactNode {
  const { t, i18n } = useTranslation();
  const records = useQuery({
    queryKey: attendanceKeys.records(companyId, employeeId, businessDate),
    queryFn: () => attendance.records(companyId, employeeId, businessDate),
  });

  const statusOf = (statusTypeId: string | undefined): string => {
    const found = statuses.find((status) => status.id === statusTypeId);
    if (found === undefined) {
      return statusTypeId ?? '—';
    }
    return i18n.language.startsWith('en')
      ? (found.labelEn ?? found.labelKo ?? found.code ?? '—')
      : (found.labelKo ?? found.labelEn ?? found.code ?? '—');
  };

  const rows = records.data ?? [];

  return (
    <section className="page__surface whosin-day" aria-label={t('whosin.dayTitle', { name, date: businessDate })}>
      <header className="whosin-day__header">
        <h2 className="whosin-day__title">{t('whosin.dayTitle', { name, date: businessDate })}</h2>
        <Button onClick={onClose}>{t('whosin.closeDay')}</Button>
      </header>

      <DataTable<AttendanceRecord>
        caption={t('whosin.dayTitle', { name, date: businessDate })}
        emptyMessage={t('whosin.dayEmpty')}
        rows={rows}
        rowKey={(record) => record.id ?? String(rows.indexOf(record))}
        columns={dayColumns(t, statusOf)}
      />

      <MidnightNote records={rows} businessDate={businessDate} />
    </section>
  );
}

/**
 * The sentence that makes 27:00 ordinary.
 *
 * Shown once under the day rather than on every row: the fact is about the day,
 * and repeating it four times would turn an explanation into an alarm.
 */
function MidnightNote({
  records,
  businessDate,
}: {
  readonly records: readonly AttendanceRecord[];
  readonly businessDate: string;
}): ReactNode {
  const { t } = useTranslation();
  const crossing = records.find((record) => crossesMidnight(record));
  const ended = readInstant(crossing?.endedAt);

  if (ended === null) {
    return null;
  }

  return (
    <p className="whosin-day__midnight">
      {t('attendance.crossesMidnight', {
        date: crossing?.businessDate ?? businessDate,
        clock: formatClockFace(ended.offsetSeconds),
      })}
    </p>
  );
}

/**
 * The statuses this company defined, with what each one does.
 *
 * Both labels, because a status carries its Korean and its English and an
 * administrator setting one up needs to see both. The behaviour flags are here
 * rather than hidden in an admin screen for the same reason people ask about
 * them here: "does 재택 count as working?" is a who's-in question.
 */
function Legend({ statuses }: { readonly statuses: readonly StatusType[] }): ReactNode {
  const { t } = useTranslation();

  if (statuses.length === 0) {
    return null;
  }

  return (
    <section className="page__surface whosin-legend" aria-labelledby="whosin-legend">
      <h2 id="whosin-legend" className="whosin-legend__title">
        {t('whosin.legendTitle')}
      </h2>
      <ul className="whosin-legend__list">
        {statuses.map((status) => {
          const colour = safeColour(status.colour);
          const behaviour = status.behaviour;
          const expires = behaviour?.autoExpiresAfterSeconds;
          return (
            <li key={status.id ?? status.code} className="whosin-legend__item">
              <span
                className="whosin-chip"
                style={colour === null ? undefined : { borderColor: colour, color: colour }}
              >
                {status.icon === undefined ? null : (
                  <span className="whosin-chip__icon" aria-hidden="true">
                    {status.icon}
                  </span>
                )}
                {status.labelKo ?? status.code}
              </span>
              <span className="att-muted">{status.labelEn ?? ''}</span>
              <span className="whosin-legend__flags">
                {behaviour?.countsAsWorking === true ? (
                  <Badge tone="positive">{t('whosin.behaviourCountsAsWorking')}</Badge>
                ) : null}
                {behaviour?.requiresApproval === true ? (
                  <Badge tone="accent">{t('whosin.behaviourRequiresApproval')}</Badge>
                ) : null}
                {behaviour?.deductsLeaveBalance === true ? (
                  <Badge tone="warning">{t('whosin.behaviourDeductsLeave')}</Badge>
                ) : null}
                {behaviour?.visibleToPeers === false ? (
                  <Badge>{t('whosin.behaviourPrivate')}</Badge>
                ) : null}
                {expires === undefined ? null : (
                  <Badge>
                    {t('whosin.behaviourExpires', { hours: Math.round(expires / 3600) })}
                  </Badge>
                )}
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
