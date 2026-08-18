/**
 * Batches.
 *
 * A batch is first-class after the write, not a receipt for it: it is listed,
 * fetched and voided as a unit. The one kind with a reporting consequence —
 * `CLOSING`, which the income statement leaves out so a closed year still
 * reports what it earned — says so on the row and again on the batch, rather
 * than leaving someone to wonder why a number moved.
 */
import { Badge, Banner, BusinessInstantText, Button, DataTable, EmptyState, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import { EntryStatusBadge } from './Journal.js';
import { Money } from './money.js';
import { accounting, keys, readInstant, type Batch, type Entry } from './queries.js';

function BatchKindBadge({ kind }: { readonly kind: string | undefined }): ReactNode {
  const { t } = useTranslation();
  const label = t(`ledger.batches.kind.${kind ?? 'UNKNOWN'}`, { defaultValue: kind ?? '' });
  return <Badge tone={kind === 'CLOSING' ? 'warning' : 'default'}>{label}</Badge>;
}

export function BatchesScreen(): ReactNode {
  const { t } = useTranslation();
  const { bookId } = useBook();
  const navigate = useNavigate();

  const batchesQuery = useQuery({
    queryKey: keys.batches(bookId ?? ''),
    queryFn: () => accounting.batches(bookId ?? ''),
    enabled: bookId !== null,
  });

  const columns: readonly Column<Batch>[] = [
    { key: 'label', header: t('ledger.batches.columnLabel'), render: (batch) => batch.label ?? '' },
    {
      key: 'kind',
      header: t('ledger.batches.columnKind'),
      width: '10rem',
      render: (batch) => <BatchKindBadge kind={batch.kind} />,
    },
    {
      key: 'entries',
      header: t('ledger.batches.columnEntries'),
      numeric: true,
      width: '8rem',
      render: (batch) => <span className="ci-numeric">{(batch.entries ?? []).length}</span>,
    },
    { key: 'createdBy', header: t('ledger.batches.columnCreatedBy'), width: '14rem', render: (batch) => batch.createdBy ?? '' },
  ];

  if (bookId === null) {
    return <EmptyState message={t('ledger.book.none')} />;
  }

  return (
    <>
      <p className="acc-note">{t('ledger.batches.hint')}</p>
      <p className="acc-note">{t('ledger.batches.closingExcluded')}</p>

      {batchesQuery.isError ? <Banner tone="danger">{presentError(batchesQuery.error, t).message}</Banner> : null}

      <DataTable
        caption={t('ledger.batches.title')}
        columns={columns}
        rows={batchesQuery.data ?? []}
        rowKey={(batch) => batch.id ?? ''}
        emptyMessage={t('ledger.batches.empty')}
        onRowActivate={(batch) => {
          if (batch.id !== undefined) {
            void navigate({ to: `/accounting/batches/${batch.id}` });
          }
        }}
      />
    </>
  );
}

export function BatchDetailScreen({ batchId }: { readonly batchId: string }): ReactNode {
  const { t } = useTranslation();
  const { bookId } = useBook();
  const queryClient = useQueryClient();

  const [voiding, setVoiding] = useState(false);
  const [reason, setReason] = useState('');
  const [attempted, setAttempted] = useState(false);

  const batchQuery = useQuery({ queryKey: keys.batch(batchId), queryFn: () => accounting.batch(batchId) });

  const voidBatch = useMutation({
    mutationFn: (input: { readonly reason: string }) => accounting.voidBatch(batchId, { reason: input.reason }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.batch(batchId) });
      await queryClient.invalidateQueries({ queryKey: keys.batches(bookId ?? '') });
      await queryClient.invalidateQueries({ queryKey: keys.entries(bookId ?? '') });
      setVoiding(false);
      setReason('');
    },
  });

  if (batchQuery.isPending) {
    return <p className="acc-note">{t('app.loading')}</p>;
  }
  if (batchQuery.isError) {
    return <Banner tone="danger">{presentError(batchQuery.error, t).message}</Banner>;
  }

  const batch = batchQuery.data;
  const entries = batch.entries ?? [];

  const entryColumns: readonly Column<Entry>[] = [
    {
      key: 'postedAt',
      header: t('ledger.entries.columnPostedAt'),
      width: '13rem',
      render: (entry) => {
        const instant = readInstant(entry.postedAt);
        return instant === null ? <span className="acc-muted">—</span> : <BusinessInstantText value={instant} />;
      },
    },
    { key: 'description', header: t('ledger.entries.columnDescription'), render: (entry) => entry.description ?? '' },
    {
      key: 'debits',
      header: t('ledger.entries.columnDebits'),
      numeric: true,
      width: '12rem',
      render: (entry) => <Money value={entry.totalDebits} />,
    },
    {
      key: 'status',
      header: t('ledger.entries.columnStatus'),
      width: '8rem',
      render: (entry) => <EntryStatusBadge status={entry.status} />,
    },
  ];

  return (
    <>
      <div className="acc-entry-head">
        <h2 className="acc-panel__title">{batch.label}</h2>
        <div className="acc-entry-facts">
          <BatchKindBadge kind={batch.kind} />
          <span className="acc-muted">{t('ledger.batches.entryCount', { count: entries.length })}</span>
          <span className="acc-muted">
            {t('ledger.batches.createdBy')}: {batch.createdBy ?? '—'}
          </span>
          <span className="acc-muted ci-numeric">{batch.id}</span>
        </div>
      </div>

      {batch.kind === 'CLOSING' ? <Banner tone="warning">{t('ledger.batches.closingExcluded')}</Banner> : null}
      {batch.note === undefined ? null : (
        <p className="acc-note">
          {t('ledger.batches.note')}: {batch.note}
        </p>
      )}

      {batch.generatorParams === undefined ? null : (
        <details className="acc-details">
          <summary>{t('ledger.batches.generator')}</summary>
          <p className="acc-note">{t('ledger.batches.generatorHint')}</p>
          <pre className="acc-pre">{batch.generatorParams}</pre>
        </details>
      )}

      <DataTable
        caption={t('ledger.batches.title')}
        columns={entryColumns}
        rows={entries}
        rowKey={(entry) => entry.id ?? ''}
        emptyMessage={t('ledger.entries.empty')}
      />

      {voiding ? (
        <form
          className="acc-form"
          onSubmit={(event) => {
            event.preventDefault();
            setAttempted(true);
            if (reason.trim() === '') {
              return;
            }
            voidBatch.mutate({ reason: reason.trim() });
          }}
        >
          <Banner tone="danger">{t('ledger.batches.voidConfirm', { count: entries.length })}</Banner>
          <TextField
            label={t('ledger.entries.reason')}
            hint={t('ledger.entries.reasonHint')}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            required
            {...(attempted && reason.trim() === '' ? { error: t('ledger.entries.reasonRequired') } : {})}
          />
          {voidBatch.isError ? <Banner tone="danger">{presentError(voidBatch.error, t).message}</Banner> : null}
          <div className="acc-actions">
            <Button tone="danger" type="submit" busy={voidBatch.isPending}>
              {t('ledger.batches.void')}
            </Button>
            <Button onClick={() => setVoiding(false)}>{t('action.cancel')}</Button>
          </div>
        </form>
      ) : (
        <div className="acc-actions">
          <Button tone="danger" onClick={() => setVoiding(true)}>
            {t('ledger.batches.void')}
          </Button>
        </div>
      )}

      {voidBatch.isSuccess ? (
        <Banner tone="positive">{t('ledger.batches.voided', { count: voidBatch.data.voidedEntries ?? 0 })}</Banner>
      ) : null}
    </>
  );
}
