import type { components } from '@coreintra/api-client';
import type { TFunction } from 'i18next';
import { Badge, BusinessInstantText, DataTable, type Column } from '@coreintra/ui';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { tryParseBusinessInstant } from '../../session/instants.js';

export type AuditEntry = components['schemas']['Entry'];

/**
 * The audit table, with both clocks.
 *
 * Business time and UTC sit in two columns and are never collapsed into one.
 * They disagree on purpose — a shift that ran to 03:00 is `27:00` on the
 * previous business day, and a row written at 02:59:59Z on the 31st may belong
 * to the 30th — and that disagreement is precisely what an auditor came to
 * read. Showing one "corrected" timestamp would destroy the evidence and look
 * tidier doing it.
 */
export function AuditTable({
  rows,
  emptyMessage,
}: {
  readonly rows: readonly AuditEntry[];
  readonly emptyMessage: string;
}): ReactNode {
  const { t } = useTranslation();

  const columns: readonly Column<AuditEntry>[] = [
    {
      key: 'businessTime',
      header: t('audit.colBusinessTime'),
      numeric: true,
      render: (entry) => {
        const instant = tryParseBusinessInstant(entry.businessInstant);
        return instant === null ? (
          <span className="ci-numeric">{entry.businessInstant ?? '-'}</span>
        ) : (
          <BusinessInstantText value={instant} />
        );
      },
    },
    {
      key: 'recordedAt',
      header: t('audit.colRecordedAt'),
      numeric: true,
      // Rendered exactly as stored. Reformatting a UTC instant into the
      // reader's local time would answer a question nobody asked and hide the
      // one they did.
      render: (entry) => <span className="ci-numeric">{entry.recordedAt ?? '-'}</span>,
    },
    {
      key: 'actor',
      header: t('audit.colActor'),
      render: (entry) => entry.actorDisplayName ?? entry.actorAccountId ?? '-',
    },
    {
      key: 'actorKind',
      header: t('audit.colActorKind'),
      render: (entry) => actorKindLabel(entry.actorKind, t),
    },
    { key: 'action', header: t('audit.colAction'), render: (entry) => entry.action ?? '-' },
    {
      key: 'resource',
      header: t('audit.colResource'),
      render: (entry) =>
        entry.resourceId === undefined
          ? (entry.resource ?? '-')
          : `${entry.resource ?? ''} ${entry.resourceId}`,
    },
    {
      key: 'capability',
      header: t('audit.colCapability'),
      render: (entry) => entry.capability ?? '-',
    },
    {
      key: 'outcome',
      header: t('audit.colOutcome'),
      render: (entry) =>
        isDenied(entry.outcome) ? (
          <Badge tone="danger">{t('audit.outcomeDenied')}</Badge>
        ) : (
          <Badge tone="positive">{t('audit.outcomeAllowed')}</Badge>
        ),
    },
    {
      key: 'rows',
      header: t('audit.colRows'),
      numeric: true,
      render: (entry) => (entry.rowsTouched === undefined ? '-' : String(entry.rowsTouched)),
    },
    {
      key: 'requestId',
      header: t('audit.colRequestId'),
      render: (entry) => entry.requestId ?? '-',
    },
  ];

  return (
    <DataTable
      caption={t('audit.title')}
      columns={columns}
      rows={rows}
      rowKey={(entry) => entry.id ?? `${entry.recordedAt ?? ''}:${entry.requestId ?? ''}`}
      emptyMessage={emptyMessage}
    />
  );
}

function isDenied(outcome: string | undefined): boolean {
  return (outcome ?? '').toUpperCase().includes('DEN');
}

function actorKindLabel(kind: string | undefined, t: TFunction): string {
  switch ((kind ?? '').toUpperCase()) {
    case 'USER':
    case 'PERSON':
      return t('audit.actorKindUser');
    case 'SERVICE':
    case 'SERVICE_ACCOUNT':
      return t('audit.actorKindService');
    case 'SUPPORT':
    case 'TEMPORARY_MASTER':
      return t('audit.actorKindSupport');
    case 'JOB':
    case 'SCHEDULED_JOB':
      return t('audit.actorKindJob');
    default:
      return kind ?? '-';
  }
}
