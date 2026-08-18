import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { callsTo, json, mountAccounting, route, seedBook } from './harness.js';

const ENTRY = {
  id: 'e-live',
  bookId: 'b-1',
  description: '임차료',
  status: 'POSTED',
  reportable: true,
  totalDebits: '300000',
  postedAt: '2026-08-18T09:00:00.000',
  revisions: [],
  postings: [
    { accountId: '1100', side: 'debit', amount: '300000', currencyCode: 'KRW' },
    { accountId: '4100', side: 'credit', amount: '300000', currencyCode: 'KRW' },
  ],
};

describe('putting an entry right', () => {
  beforeEach(() => {
    seedBook();
    route('GET /accounting/entries/e-live', () => json(ENTRY, { etag: 'W/"7"' }));
    route('PATCH /accounting/entries/e-live', () => json({ ...ENTRY, revisions: [{ number: 1, kind: 'UPDATE' }] }));
    route('POST /accounting/entries/e-live/void', () => json({ ...ENTRY, status: 'VOID', reportable: false }));
  });

  it('offers correcting and voiding as different things, each explaining itself', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/e-live');

    await user.click(await screen.findByRole('button', { name: t('ledger.entries.correct') }));
    expect(screen.getByText(t('ledger.entries.correctExplain'))).toBeDefined();

    await user.click(screen.getByRole('button', { name: t('ledger.entries.void') }));
    expect(screen.getByText(t('ledger.entries.voidExplain'))).toBeDefined();
  });

  it('will not correct without a reason, and sends If-Match when it does', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/e-live');

    await user.click(await screen.findByRole('button', { name: t('ledger.entries.correct') }));
    await user.click(screen.getByRole('button', { name: t('ledger.entries.correctSubmit') }));

    expect(screen.getByText(t('ledger.entries.reasonRequired'))).toBeDefined();
    expect(callsTo('PATCH', '/accounting/entries/e-live')).toHaveLength(0);

    await user.type(screen.getByLabelText(t('ledger.entries.reason')), '금액을 잘못 적었습니다');
    await user.click(screen.getByRole('button', { name: t('ledger.entries.correctSubmit') }));

    await waitFor(() => {
      expect(callsTo('PATCH', '/accounting/entries/e-live')).toHaveLength(1);
    });

    const call = callsTo('PATCH', '/accounting/entries/e-live')[0];
    expect(call?.headers['if-match']).toBe('W/"7"');
    const body = call?.body as { reason?: string } | undefined;
    expect(body?.reason).toBe('금액을 잘못 적었습니다');
  });

  it('will not void without a reason either', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/e-live');

    await user.click(await screen.findByRole('button', { name: t('ledger.entries.void') }));
    await user.click(screen.getByRole('button', { name: t('ledger.entries.voidSubmit') }));

    expect(callsTo('POST', '/accounting/entries/e-live/void')).toHaveLength(0);

    await user.type(screen.getByLabelText(t('ledger.entries.reason')), '없던 거래입니다');
    await user.click(screen.getByRole('button', { name: t('ledger.entries.voidSubmit') }));

    await waitFor(() => {
      expect(callsTo('POST', '/accounting/entries/e-live/void')).toHaveLength(1);
    });
  });
});

describe('a batch', () => {
  const BATCH = {
    id: 'batch-1',
    bookId: 'b-1',
    kind: 'CLOSING',
    label: '2025 마감',
    createdBy: 'a-1',
    entries: [
      { id: 'e-a', description: '마감 분개', status: 'POSTED', totalDebits: '10000', postedAt: '2025-12-31T09:00:00.000' },
      { id: 'e-b', description: '마감 분개 2', status: 'POSTED', totalDebits: '20000', postedAt: '2025-12-31T09:00:00.000' },
    ],
  };

  beforeEach(() => {
    seedBook();
    route('GET /accounting/books/b-1/batches', () => json([BATCH]));
    route('GET /accounting/batches/batch-1', () => json(BATCH));
    route('POST /accounting/batches/batch-1/void', () => json({ batchId: 'batch-1', voidedEntries: 2 }));
  });

  it('says why a closing batch is missing from the income statement', async () => {
    const { t } = mountAccounting('/accounting/batches/batch-1');

    expect(await screen.findByText('2025 마감')).toBeDefined();
    expect(screen.getAllByText(t('ledger.batches.closingExcluded')).length).toBeGreaterThan(0);
  });

  it('voids as a unit, naming how many entries that is', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/batches/batch-1');

    await user.click(await screen.findByRole('button', { name: t('ledger.batches.void') }));
    expect(screen.getByText(t('ledger.batches.voidConfirm', { count: 2 }))).toBeDefined();

    await user.type(screen.getByLabelText(t('ledger.entries.reason')), '중복으로 올렸습니다');
    const buttons = screen.getAllByRole('button', { name: t('ledger.batches.void') });
    await user.click(buttons[buttons.length - 1] as HTMLElement);

    await waitFor(() => {
      expect(callsTo('POST', '/accounting/batches/batch-1/void')).toHaveLength(1);
    });
    expect(await screen.findByText(t('ledger.batches.voided', { count: 2 }))).toBeDefined();
  });
});
