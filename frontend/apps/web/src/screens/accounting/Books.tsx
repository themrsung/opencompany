/**
 * What the accounting area opens on: which books this company keeps, and which
 * one everything else on these screens is about.
 *
 * Opening a book is not offered here. It is a once-per-entity act that decides
 * the base currency for every figure that follows, and it belongs with the
 * people who set the company up rather than with a button beside a list.
 */
import { Badge, Banner, DataTable, type Column } from '@coreintra/ui';
import { useNavigate } from '@tanstack/react-router';
import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { useBook } from './book.js';
import type { Book } from './queries.js';

export function BooksScreen(): ReactNode {
  const { t } = useTranslation();
  const { books, bookId, selectBook } = useBook();
  const navigate = useNavigate();

  const columns: readonly Column<Book>[] = [
    {
      key: 'name',
      header: t('ledger.book.label'),
      render: (book) => (
        <>
          {book.name ?? book.id}
          {book.id === bookId ? (
            <>
              {' '}
              <Badge tone="accent">{t('ledger.book.pick')}</Badge>
            </>
          ) : null}
        </>
      ),
    },
    { key: 'base', header: t('ledger.book.base'), width: '10rem', render: (book) => book.baseCurrencyCode ?? '' },
    {
      key: 'status',
      header: t('common.status'),
      width: '10rem',
      render: (book) =>
        book.retired === true ? <Badge tone="warning">{t('ledger.book.retired')}</Badge> : <span />,
    },
  ];

  return (
    <>
      <Banner tone="info" title={t('ledger.book.pick')}>
        {t('ledger.book.openHint')}
      </Banner>

      <DataTable
        caption={t('ledger.book.label')}
        columns={columns}
        rows={books}
        rowKey={(book) => book.id ?? ''}
        emptyMessage={t('ledger.book.none')}
        onRowActivate={(book) => {
          if (book.id !== undefined) {
            selectBook(book.id);
            void navigate({ to: '/accounting/accounts' });
          }
        }}
      />
    </>
  );
}
