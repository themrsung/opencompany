/**
 * The chrome every accounting screen sits inside: which book is open, the tab
 * strip, and the exact-value switch.
 *
 * The switch lives here rather than on one screen because §9 wants the exact
 * stored value reachable from **every** screen that shows a number, and the
 * accounting header is the one piece of furniture all of them share. Flipping
 * it on the trial balance and finding the journal still rounded would be the
 * bug the requirement is about.
 */
import { Banner, useFullDecimal } from '@coreintra/ui';
import { Link, Outlet, useNavigate } from '@tanstack/react-router';
import { useEffect, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';

import './accounting.css';
import { BookProvider, useBook } from './book.js';
import { Select } from './fields.js';
import { ExactValuesToggle } from './money.js';

const TABS: readonly { readonly to: string; readonly key: string }[] = [
  { to: '/accounting/accounts', key: 'accounts' },
  { to: '/accounting/entries', key: 'entries' },
  { to: '/accounting/batches', key: 'batches' },
  { to: '/accounting/reports', key: 'reports' },
  { to: '/accounting/amortisation', key: 'amortisation' },
];

export function AccountingArea(): ReactNode {
  return (
    <BookProvider>
      <AreaChrome />
    </BookProvider>
  );
}

function AreaChrome(): ReactNode {
  const { t } = useTranslation();
  const { companies, books, companyId, bookId, book, selectCompany, selectBook, error } = useBook();
  const navigate = useNavigate();
  const { showExact, setShowExact } = useFullDecimal();

  /**
   * Alt+N writes an entry, Alt+E reveals the exact figures.
   *
   * Both are modified: a bare letter would fire while someone was halfway
   * through typing an account name.
   */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (!event.altKey || event.ctrlKey || event.metaKey) {
        return;
      }
      if (event.key === 'n' || event.key === 'N') {
        event.preventDefault();
        void navigate({ to: '/accounting/entries/new' });
      }
      if (event.key === 'e' || event.key === 'E') {
        event.preventDefault();
        setShowExact(!showExact);
      }
    };
    globalThis.addEventListener('keydown', onKeyDown);
    return () => {
      globalThis.removeEventListener('keydown', onKeyDown);
    };
  }, [navigate, setShowExact, showExact]);

  return (
    <Page
      title={t('accounting.title')}
      actions={
        <div className="acc-header-actions">
          <ExactValuesToggle />
        </div>
      }
    >
      <div className="acc-bookbar">
        <Select
          className="acc-bookbar__field"
          label={t('ledger.book.company')}
          value={companyId ?? ''}
          onChange={selectCompany}
        >
          {companies.length === 0 ? <option value="">{t('ledger.book.companyNone')}</option> : null}
          {companies.map((company) => (
            <option key={company.id ?? ''} value={company.id ?? ''}>
              {company.nameKo ?? company.nameEn ?? company.id}
            </option>
          ))}
        </Select>

        <Select
          className="acc-bookbar__field"
          label={t('ledger.book.label')}
          value={bookId ?? ''}
          onChange={selectBook}
        >
          {books.length === 0 ? <option value="">{t('ledger.book.none')}</option> : null}
          {books.map((candidate) => (
            <option key={candidate.id ?? ''} value={candidate.id ?? ''}>
              {candidate.name ?? candidate.id}
              {candidate.retired === true ? ` · ${t('ledger.book.retired')}` : ''}
            </option>
          ))}
        </Select>

        <p className="acc-bookbar__base">
          {t('ledger.book.base')}: <strong>{book?.baseCurrencyCode ?? '—'}</strong>
        </p>
        <p className="acc-muted acc-bookbar__hint">{t('ledger.shortcutHint')}</p>
      </div>

      {error === null || error === undefined ? null : (
        <Banner tone="danger">{presentError(error, t).message}</Banner>
      )}

      <nav className="acc-tabs" aria-label={t('accounting.title')}>
        {TABS.map((tab) => (
          <Link key={tab.to} to={tab.to} className="acc-tab" activeProps={{ className: 'acc-tab acc-tab--active' }}>
            {t(`ledger.tab.${tab.key}`)}
          </Link>
        ))}
      </nav>

      <div className="page__surface acc-surface">
        <Outlet />
      </div>
    </Page>
  );
}
