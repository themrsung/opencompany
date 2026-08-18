import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { json, mountAccounting, route, seedBook } from './harness.js';

/**
 * KRW prints no decimals, and the ledger stores whatever it was given. A figure
 * that is being abbreviated has to look different from one that is not, and the
 * stored value has to be reachable — from the pointer, from the keyboard, and
 * from the global switch.
 */
describe('a KRW figure with more precision than KRW prints', () => {
  beforeEach(() => {
    seedBook();
    route('GET /accounting/books/b-1/entries', () =>
      json({
        items: [
          {
            id: 'e-1',
            bookId: 'b-1',
            description: '임차료',
            status: 'POSTED',
            reportable: true,
            totalDebits: '1400000.25',
            postedAt: '2026-08-18T09:00:00.000',
            postings: [],
            revisions: [],
          },
        ],
        nextCursor: null,
      }),
    );
  });

  it('shows the rounded figure, marks it, and names the stored value', async () => {
    const { t } = mountAccounting('/accounting/entries');

    expect(await screen.findByText('1,400,000')).toBeDefined();
    // The marker is a real control with the exact value as its accessible name.
    expect(screen.getByLabelText(t('common.roundedNotice', { exact: '1,400,000.25' }))).toBeDefined();
  });

  it('reveals every stored value at once when the exact-values switch is on', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries');

    expect(await screen.findByText('1,400,000')).toBeDefined();
    expect(screen.queryByText('1,400,000.25')).toBeNull();

    await user.click(screen.getByLabelText(t('common.showExactValues')));

    expect(await screen.findByText('1,400,000.25')).toBeDefined();
  });

  it('keeps a business instant that left the calendar day intact', async () => {
    route('GET /accounting/books/b-1/entries', () =>
      json({
        items: [
          {
            id: 'e-2',
            bookId: 'b-1',
            description: '야간 작업',
            status: 'POSTED',
            reportable: true,
            totalDebits: '1000',
            postedAt: '2026-08-17T27:00:00.000',
            postings: [],
            revisions: [],
          },
        ],
        nextCursor: null,
      }),
    );
    mountAccounting('/accounting/entries');

    // 27:00 on the 17th, not 03:00 on the 18th.
    expect(await screen.findByText(/2026-08-17\s+27:00/)).toBeDefined();
  });
});
