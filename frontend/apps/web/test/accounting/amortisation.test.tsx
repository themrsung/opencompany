import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { callsTo, json, mountAccounting, route, seedBook } from './harness.js';

const PREVIEW = '/accounting/books/b-1/amortisation/preview';
const BATCHES = '/accounting/books/b-1/batches';

const SCHEDULE = {
  baseAmount: '1200000',
  residualValue: '0',
  months: 3,
  roundingDigits: 0,
  remainderTo: 'END',
  total: '1200000',
  generatorParams: '{"kind":"straight-line","months":3}',
  instalments: [
    { number: 1, businessDate: '2026-01-01', amount: '400000', carryingAmount: '800000' },
    { number: 2, businessDate: '2026-02-01', amount: '400000', carryingAmount: '400000' },
    { number: 3, businessDate: '2026-03-01', amount: '400000', carryingAmount: '0' },
  ],
  entries: [
    {
      description: '보험료 상각 1/3',
      postedAt: '2026-01-01T00:00:00.000',
      postings: [
        { accountId: '1100', side: 'debit', amount: '400000', currencyCode: 'KRW' },
        { accountId: '4100', side: 'credit', amount: '400000', currencyCode: 'KRW' },
      ],
    },
  ],
};

async function fillAndPreview(user: ReturnType<typeof userEvent.setup>, t: (key: string) => string): Promise<void> {
  await screen.findAllByRole('option', { name: /1100/ });
  await user.selectOptions(screen.getByLabelText(t('ledger.amortisation.debitAccount')), '1100');
  await user.selectOptions(screen.getByLabelText(t('ledger.amortisation.creditAccount')), '4100');
  await user.type(screen.getByLabelText(t('ledger.amortisation.description')), '보험료 상각');
  await user.type(screen.getByLabelText(t('ledger.amortisation.baseAmount')), '1200000');
  await user.clear(screen.getByLabelText(t('ledger.amortisation.months')));
  await user.type(screen.getByLabelText(t('ledger.amortisation.months')), '3');
  await user.type(screen.getByLabelText(t('ledger.amortisation.startMonth')), '2026-01');
  await user.click(screen.getByRole('button', { name: t('ledger.amortisation.preview') }));
}

describe('the amortisation preview', () => {
  beforeEach(() => {
    seedBook();
    route(`POST ${PREVIEW}`, () => json(SCHEDULE));
    route(`POST ${BATCHES}`, () => json({ id: 'batch-1', bookId: 'b-1', kind: 'AMORTIZATION', label: '보험료 상각', entries: [] }));
  });

  it('computes a schedule and posts nothing at all', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/amortisation');

    await fillAndPreview(user, t);

    await waitFor(() => {
      expect(callsTo('POST', PREVIEW)).toHaveLength(1);
    });
    expect(await screen.findByText('800,000')).toBeDefined();

    // The whole point: a preview is not a fact.
    expect(callsTo('POST', BATCHES)).toHaveLength(0);
    expect(screen.getByText(t('ledger.amortisation.nothingPosted'))).toBeDefined();
    expect(screen.getByText(t('ledger.amortisation.explain'))).toBeDefined();
  });

  it('reaches the ledger only when a person posts it, and then as an amortisation batch', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/amortisation');

    await fillAndPreview(user, t);
    await screen.findByText('800,000');

    // Two deliberate acts, not one: ask to post, then confirm.
    await user.click(screen.getByRole('button', { name: t('ledger.amortisation.post') }));
    expect(callsTo('POST', BATCHES)).toHaveLength(0);

    const confirm = screen.getAllByRole('button', { name: t('ledger.amortisation.post') });
    await user.click(confirm[confirm.length - 1] as HTMLElement);

    await waitFor(() => {
      expect(callsTo('POST', BATCHES)).toHaveLength(1);
    });

    const sent = callsTo('POST', BATCHES)[0]?.body as {
      kind: string;
      generatorParams: string;
      entries: { postings: { amount: string }[] }[];
    };
    expect(sent.kind).toBe('AMORTIZATION');
    // The generator parameters travel as lineage, exactly as the server sent them.
    expect(sent.generatorParams).toBe(SCHEDULE.generatorParams);
    expect(sent.entries[0]?.postings[0]?.amount).toBe('400000');
  });
});
