import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { json, mountAccounting, route, seedBook } from './harness.js';

const VOIDED = {
  id: 'e-void',
  bookId: 'b-1',
  description: '잘못 올린 전표',
  status: 'VOID',
  reportable: false,
  totalDebits: '5000',
  postedAt: '2026-08-17T09:00:00.000',
  postings: [],
  revisions: [{ number: 1, kind: 'VOID', reason: '거래 자체가 없었습니다', at: '2026-08-18T10:00:00.000', actorAccountId: 'a-1' }],
};

const POSTED = {
  id: 'e-live',
  bookId: 'b-1',
  description: '임차료',
  status: 'POSTED',
  reportable: true,
  totalDebits: '300000',
  postedAt: '2026-08-18T09:00:00.000',
  postings: [],
  revisions: [],
};

describe('the balance sheet', () => {
  beforeEach(seedBook);

  it('shows a false balanced flag as an alarm carrying the exact difference', async () => {
    route('GET /accounting/books/b-1/reports/balance-sheet', () =>
      json({
        asOf: '2026-08-18',
        assets: '1000000',
        liabilities: '400000',
        equity: '599987.5',
        unclosedNetIncome: '0',
        balanced: false,
        imbalance: '12.5',
      }),
    );

    const { t, container } = mountAccounting('/accounting/reports/balance-sheet');

    const alarm = await screen.findByRole('alert');
    expect(alarm.textContent).toContain(t('ledger.reports.balancedAlarmTitle'));
    expect(alarm.textContent).toContain('12.5');

    // Not absorbed, and not dressed up as a clean statement either.
    expect(container.querySelector('.acc-statement--suspect')).not.toBeNull();
    expect(screen.queryByText(t('accounting.balancedFlag'))).toBeNull();
  });

  it('says so plainly when it does balance', async () => {
    route('GET /accounting/books/b-1/reports/balance-sheet', () =>
      json({
        asOf: '2026-08-18',
        assets: '1000000',
        liabilities: '400000',
        equity: '600000',
        unclosedNetIncome: '0',
        balanced: true,
        imbalance: '0',
      }),
    );

    const { t, container } = mountAccounting('/accounting/reports/balance-sheet');

    expect(await screen.findByText(t('accounting.balancedFlag'))).toBeDefined();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(container.querySelector('.acc-statement--suspect')).toBeNull();
  });
});

describe('a voided entry', () => {
  beforeEach(() => {
    seedBook();
    route('GET /accounting/books/b-1/entries', () => json({ items: [POSTED, VOIDED], nextCursor: null }));
    route('GET /accounting/entries/e-void', () => json(VOIDED, { etag: 'W/"1"' }));
    route('GET /accounting/books/b-1/reports/trial-balance', () =>
      json({
        asOf: '2026-08-18',
        balanced: true,
        imbalance: '0',
        totalDebits: '300000',
        totalCredits: '300000',
        lines: [
          { accountId: '1100', debit: '300000', credit: '0', net: '300000' },
          { accountId: '4100', debit: '0', credit: '300000', net: '-300000' },
        ],
      }),
    );
  });

  it('stays in the journal, marked as voided', async () => {
    const { t } = mountAccounting('/accounting/entries');

    expect(await screen.findByText('잘못 올린 전표')).toBeDefined();
    expect(screen.getByText(t('ledger.entries.status.VOID'))).toBeDefined();
  });

  it('is missing from the trial balance the server computed', async () => {
    mountAccounting('/accounting/reports/trial-balance');

    expect((await screen.findAllByText('300,000')).length).toBeGreaterThan(0);
    // The voided entry's figure appears nowhere in the report.
    expect(screen.queryByText('5,000')).toBeNull();
    expect(screen.queryByText('잘못 올린 전표')).toBeNull();
  });

  it('explains itself on the entry, with the reason kept as a revision', async () => {
    const { t } = mountAccounting('/accounting/entries/e-void');

    expect(await screen.findByText(t('accounting.voided'))).toBeDefined();
    expect(screen.getByText('거래 자체가 없었습니다')).toBeDefined();
    // Voiding is done; correcting a void is not on offer.
    expect(screen.queryByRole('button', { name: t('ledger.entries.correct') })).toBeNull();
  });
});
