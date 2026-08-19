/**
 * One entry, and the two ways of putting it right.
 *
 * They are different operations for different mistakes and the screen says
 * which is which before either is opened:
 *
 *   - **Correct** rewrites the postings and keeps a numbered revision. Use it
 *     when the transaction happened but a figure was wrong.
 *   - **Void** takes the entry out of every report and leaves it in the
 *     journal. Use it when the transaction should never have been recorded.
 *
 * Both demand a reason, because the revision trail is only worth keeping if it
 * says why.
 */
import { Badge, Banner, BusinessInstantText, Button, DataTable, TextField, type Column } from '@coreintra/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import {
  EntryEditor,
  emptyPosting,
  postingKey,
  toPostingRequests,
  type EntryDraft,
  type PostingDraft,
} from './EntryEditor.js';
import { EntryStatusBadge } from './Journal.js';
import { Money } from './money.js';
import { accounting, keys, readInstant, type Entry, type Posting, type Revision } from './queries.js';

function toPostingDraft(posting: Posting): PostingDraft {
  return {
    key: postingKey(),
    side: posting.side === 'credit' ? 'credit' : 'debit',
    accountId: posting.accountId ?? '',
    amount: posting.amount ?? '',
    currencyCode: posting.currencyCode ?? '',
    baseAmount: posting.baseAmount ?? '',
    rate: posting.rate ?? '',
    memo: posting.memo ?? '',
    clientId: posting.clientId ?? '',
  };
}

function toDraft(entry: Entry): EntryDraft {
  const postings: PostingDraft[] = (entry.postings ?? []).map(toPostingDraft);
  return {
    description: entry.description ?? '',
    postedAt: readInstant(entry.postedAt),
    clientId: '',
    draft: false,
    postings: postings.length >= 2 ? postings : [emptyPosting('debit'), emptyPosting('credit')],
  };
}

export function EntryDetailScreen({ entryId }: { readonly entryId: string }): ReactNode {
  const { t } = useTranslation();
  const { bookId, baseCurrencyCode } = useBook();
  const queryClient = useQueryClient();

  const [correcting, setCorrecting] = useState(false);
  const [voiding, setVoiding] = useState(false);
  const [voidReason, setVoidReason] = useState('');
  const [voidAttempted, setVoidAttempted] = useState(false);

  const entryQuery = useQuery({ queryKey: keys.entry(entryId), queryFn: () => accounting.entry(entryId) });

  const invalidate = async (): Promise<void> => {
    await queryClient.invalidateQueries({ queryKey: keys.entry(entryId) });
    await queryClient.invalidateQueries({ queryKey: keys.entries(bookId ?? '') });
  };

  const correct = useMutation({
    mutationFn: (input: { readonly reason: string; readonly draft: EntryDraft; readonly ifMatch: string | undefined }) =>
      accounting.correctEntry(
        entryId,
        {
          reason: input.reason,
          postings: toPostingRequests(input.draft.postings, baseCurrencyCode),
          ...(input.draft.description.trim() === '' ? {} : { description: input.draft.description.trim() }),
        },
        input.ifMatch,
      ),
    onSuccess: async () => {
      await invalidate();
      setCorrecting(false);
    },
  });

  const voidEntry = useMutation({
    mutationFn: (input: { readonly reason: string; readonly ifMatch: string | undefined }) =>
      accounting.voidEntry(entryId, { reason: input.reason }, input.ifMatch),
    onSuccess: async () => {
      await invalidate();
      setVoiding(false);
      setVoidReason('');
    },
  });

  if (entryQuery.isPending) {
    return <p className="acc-note">{t('app.loading')}</p>;
  }
  if (entryQuery.isError) {
    return <Banner tone="danger">{presentError(entryQuery.error, t).message}</Banner>;
  }

  const entry = entryQuery.data.data;
  const etag = entryQuery.data.etag ?? undefined;
  const instant = readInstant(entry.postedAt);
  const revisions = entry.revisions ?? [];

  const postingColumns: readonly Column<Posting>[] = [
    { key: 'account', header: t('ledger.entries.account'), render: (posting) => <span className="ci-numeric">{posting.accountId}</span> },
    {
      key: 'side',
      header: t('accounting.posting'),
      width: '6rem',
      render: (posting) => (posting.side === 'credit' ? t('accounting.credit') : t('accounting.debit')),
    },
    {
      key: 'amount',
      header: t('ledger.entries.amount'),
      numeric: true,
      render: (posting) => <Money value={posting.amount} currencyCode={posting.currencyCode} withCurrency />,
    },
    {
      key: 'baseAmount',
      header: t('ledger.entries.baseAmount'),
      numeric: true,
      render: (posting) => <Money value={posting.baseAmount} />,
    },
    {
      key: 'rate',
      header: t('ledger.entries.rate'),
      numeric: true,
      width: '8rem',
      render: (posting) => (posting.rate === undefined ? <span className="acc-muted">—</span> : <span className="ci-numeric">{posting.rate}</span>),
    },
    { key: 'memo', header: t('ledger.entries.memo'), render: (posting) => posting.memo ?? '' },
    { key: 'client', header: t('ledger.entries.client'), render: (posting) => posting.clientId ?? '' },
  ];

  const revisionColumns: readonly Column<Revision>[] = [
    {
      key: 'number',
      header: t('ledger.entries.revisionNumber', { no: '' }),
      width: '6rem',
      render: (revision) => <span className="ci-numeric">{revision.number}</span>,
    },
    { key: 'kind', header: t('ledger.entries.revisionKind'), width: '8rem', render: (revision) => revision.kind ?? '' },
    {
      key: 'at',
      header: t('ledger.entries.revisionAt'),
      width: '13rem',
      render: (revision) => {
        const at = readInstant(revision.at);
        return at === null ? <span className="acc-muted">—</span> : <BusinessInstantText value={at} />;
      },
    },
    { key: 'actor', header: t('ledger.entries.revisionActor'), render: (revision) => revision.actorAccountId ?? '' },
    { key: 'reason', header: t('ledger.entries.revisionReason'), render: (revision) => revision.reason ?? '' },
  ];

  return (
    <>
      <div className="acc-entry-head">
        <h2 className="acc-panel__title">{entry.description}</h2>
        <div className="acc-entry-facts">
          <EntryStatusBadge status={entry.status} />
          {instant === null ? null : <BusinessInstantText value={instant} />}
          <span className="acc-muted ci-numeric">{entry.id}</span>
          {entry.batchId === undefined ? null : <Badge tone="accent">{t('ledger.entries.inBatch', { label: entry.batchId })}</Badge>}
        </div>
      </div>

      {correct.isSuccess ? (
        <Banner tone="positive">
          {t('ledger.entries.corrected', { no: correct.data.data.revisions?.length ?? 1 })}
        </Banner>
      ) : null}
      {voidEntry.isSuccess ? <Banner tone="positive">{t('ledger.entries.voided')}</Banner> : null}

      {entry.status === 'VOID' ? <Banner tone="warning">{t('accounting.voided')}</Banner> : null}
      {entry.reportable === false && entry.status !== 'VOID' ? (
        <Banner tone="warning">{t('ledger.entries.notReportable')}</Banner>
      ) : null}

      <DataTable
        caption={t('ledger.entries.postings')}
        columns={postingColumns}
        rows={entry.postings ?? []}
        rowKey={(posting) => `${posting.accountId ?? ''}:${posting.side ?? ''}:${posting.amount ?? ''}`}
        emptyMessage={t('common.empty')}
      />

      <p className="acc-total">
        {t('ledger.entries.totalDebits')}: <Money value={entry.totalDebits} withCurrency />
      </p>

      {entry.status === 'VOID' ? null : (
        <div className="acc-actions">
          <Button onClick={() => setCorrecting((open) => !open)}>{t('ledger.entries.correct')}</Button>
          <Button tone="danger" onClick={() => setVoiding((open) => !open)}>
            {t('ledger.entries.void')}
          </Button>
        </div>
      )}

      {correcting ? (
        <EntryEditor
          variant="correct"
          initial={toDraft(entry)}
          busy={correct.isPending}
          error={correct.error}
          onCancel={() => setCorrecting(false)}
          onSubmit={(draft, reason) => correct.mutate({ draft, reason, ifMatch: etag })}
        />
      ) : null}

      {voiding ? (
        <form
          className="acc-form"
          onSubmit={(event) => {
            event.preventDefault();
            setVoidAttempted(true);
            if (voidReason.trim() === '') {
              return;
            }
            voidEntry.mutate({ reason: voidReason.trim(), ifMatch: etag });
          }}
        >
          <Banner tone="warning" title={t('ledger.entries.voidTitle')}>
            {t('ledger.entries.voidExplain')}
          </Banner>
          <TextField
            label={t('ledger.entries.reason')}
            hint={t('ledger.entries.reasonHint')}
            value={voidReason}
            onChange={(event) => setVoidReason(event.target.value)}
            required
            {...(voidAttempted && voidReason.trim() === '' ? { error: t('ledger.entries.reasonRequired') } : {})}
          />
          {voidEntry.isError ? <Banner tone="danger">{presentError(voidEntry.error, t).message}</Banner> : null}
          <div className="acc-actions">
            <Button tone="danger" type="submit" busy={voidEntry.isPending}>
              {t('ledger.entries.voidSubmit')}
            </Button>
            <Button onClick={() => setVoiding(false)}>{t('action.cancel')}</Button>
          </div>
        </form>
      ) : null}

      <h3 className="acc-section-title">{t('ledger.entries.revisions')}</h3>
      <DataTable
        caption={t('ledger.entries.revisions')}
        columns={revisionColumns}
        rows={revisions}
        rowKey={(revision) => String(revision.number ?? '')}
        emptyMessage={t('ledger.entries.revisionsEmpty')}
      />
    </>
  );
}
