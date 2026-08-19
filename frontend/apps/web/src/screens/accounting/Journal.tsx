/**
 * The journal, and the form that writes to it.
 *
 * The journal deliberately shows voided and draft entries alongside posted
 * ones: it is the record of what was done, including what was done wrongly.
 * Reports are where they disappear, and the hint at the top says so, because a
 * row that is visible here and missing from the trial balance is otherwise a
 * mystery worth an afternoon.
 */
import { BusinessInstantText, Badge, Banner, Button, DataTable, EmptyState, type Column } from '@coreintra/ui';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';

import { useBook } from './book.js';
import {
  EntryEditor,
  emptyDraft,
  startOfToday,
  toPostingRequests,
  type EntryDraft,
} from './EntryEditor.js';
import { Money } from './money.js';
import { accounting, keys, readInstant, wireInstant, type Entry, type PostEntryRequest } from './queries.js';

export function EntryStatusBadge({ status }: { readonly status: string | undefined }): ReactNode {
  const { t } = useTranslation();
  const label = t(`ledger.entries.status.${status ?? 'POSTED'}`, { defaultValue: status ?? '' });
  if (status === 'VOID') {
    return <Badge tone="danger">{label}</Badge>;
  }
  if (status === 'DRAFT') {
    return <Badge tone="warning">{label}</Badge>;
  }
  return <Badge tone="positive">{label}</Badge>;
}

export function JournalScreen(): ReactNode {
  const { t } = useTranslation();
  const { bookId } = useBook();
  const navigate = useNavigate();

  const entriesQuery = useInfiniteQuery({
    queryKey: keys.entries(bookId ?? ''),
    queryFn: ({ pageParam }) => accounting.entries(bookId ?? '', pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    enabled: bookId !== null,
  });

  const rows = useMemo(
    () => (entriesQuery.data?.pages ?? []).flatMap((page) => page.items),
    [entriesQuery.data],
  );

  const columns: readonly Column<Entry>[] = [
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
    {
      key: 'batch',
      header: t('ledger.entries.columnBatch'),
      width: '10rem',
      render: (entry) => (entry.batchId === undefined ? '' : <span className="ci-numeric">{entry.batchId}</span>),
    },
  ];

  if (bookId === null) {
    return <EmptyState message={t('ledger.book.none')} />;
  }

  return (
    <>
      <div className="acc-toolbar">
        <Button tone="primary" onClick={() => void navigate({ to: '/accounting/entries/new' })}>
          {t('ledger.entries.new')}
        </Button>
      </div>
      <p className="acc-note">{t('ledger.entries.journalHint')}</p>

      {entriesQuery.isError ? <Banner tone="danger">{presentError(entriesQuery.error, t).message}</Banner> : null}

      <DataTable
        caption={t('ledger.entries.journal')}
        columns={columns}
        rows={rows}
        rowKey={(entry) => entry.id ?? ''}
        emptyMessage={t('ledger.entries.empty')}
        onRowActivate={(entry) => {
          if (entry.id !== undefined) {
            void navigate({ to: `/accounting/entries/${entry.id}` });
          }
        }}
      />

      {entriesQuery.hasNextPage ? (
        <div className="acc-actions">
          <Button busy={entriesQuery.isFetchingNextPage} onClick={() => void entriesQuery.fetchNextPage()}>
            {t('ledger.entries.loadMore')}
          </Button>
        </div>
      ) : null}
    </>
  );
}

export function NewEntryScreen(): ReactNode {
  const { t } = useTranslation();
  const { bookId, baseCurrencyCode } = useBook();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  /**
   * One key for one logical entry, kept across retries. A new key would be a
   * new entry, which is exactly the double-post the header exists to prevent.
   */
  const [idempotencyKey] = useState(() => globalThis.crypto.randomUUID());
  const [initial] = useState<EntryDraft>(() => ({ ...emptyDraft(), postedAt: startOfToday() }));

  const post = useMutation({
    mutationFn: (body: PostEntryRequest) => accounting.postEntry(bookId ?? '', body, idempotencyKey),
    onSuccess: async (entry) => {
      await queryClient.invalidateQueries({ queryKey: keys.entries(bookId ?? '') });
      if (entry.id !== undefined) {
        await navigate({ to: `/accounting/entries/${entry.id}` });
      }
    },
  });

  if (bookId === null) {
    return <EmptyState message={t('ledger.book.none')} />;
  }

  return (
    <EntryEditor
      variant="create"
      initial={initial}
      busy={post.isPending}
      error={post.error}
      onCancel={() => void navigate({ to: '/accounting/entries' })}
      onSubmit={(draft) => {
        post.mutate({
          description: draft.description.trim(),
          postings: toPostingRequests(draft.postings, baseCurrencyCode),
          ...(draft.postedAt === null ? {} : { postedAt: wireInstant(draft.postedAt) }),
          ...(draft.clientId === '' ? {} : { clientId: draft.clientId }),
          ...(draft.draft ? { draft: true } : {}),
        });
      }}
    />
  );
}
