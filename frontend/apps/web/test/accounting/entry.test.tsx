import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { callsTo, json, mountAccounting, route, seedBook, type RecordedCall } from './harness.js';

const POST_ENTRY = '/accounting/books/b-1/entries';

async function fillTwoLines(
  user: ReturnType<typeof userEvent.setup>,
  t: (key: string) => string,
  debit: string,
  credit: string,
): Promise<void> {
  const accounts = await screen.findAllByLabelText(t('ledger.entries.account'));
  const amounts = screen.getAllByLabelText(t('ledger.entries.amount'));
  const sides = screen.getAllByLabelText(t('accounting.posting'));

  await user.selectOptions(accounts[0] as HTMLSelectElement, '1100');
  await user.selectOptions(sides[0] as HTMLSelectElement, 'debit');
  await user.type(amounts[0] as HTMLInputElement, debit);

  await user.selectOptions(accounts[1] as HTMLSelectElement, '4100');
  await user.selectOptions(sides[1] as HTMLSelectElement, 'credit');
  await user.type(amounts[1] as HTMLInputElement, credit);
}

describe('writing an entry', () => {
  beforeEach(() => {
    seedBook();
    route(`POST ${POST_ENTRY}`, (call: RecordedCall) =>
      json({ id: 'e-9', bookId: 'b-1', description: (call.body as { description: string }).description, status: 'POSTED' }),
    );
  });

  it('refuses to save while debits and credits differ, and says by how much', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/new');

    await user.type(await screen.findByLabelText(t('ledger.entries.description')), '월세');
    await fillTwoLines(user, t, '1000', '999.5');

    const alarm = screen.getAllByRole('alert').map((node) => node.textContent ?? '');
    // The exact difference, not the word "unbalanced".
    expect(alarm.some((text) => text.includes('0.5'))).toBe(true);

    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));

    expect(callsTo('POST', POST_ENTRY)).toHaveLength(0);
  });

  it('saves once the difference is exactly zero', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/new');

    await user.type(await screen.findByLabelText(t('ledger.entries.description')), '월세');
    await fillTwoLines(user, t, '1000', '1000');

    expect(screen.getByText(t('ledger.entries.balanced'))).toBeDefined();

    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));

    await waitFor(() => {
      expect(callsTo('POST', POST_ENTRY)).toHaveLength(1);
    });
  });

  it('rejects 1e3 on input and accepts 1,000', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/new');

    await user.type(await screen.findByLabelText(t('ledger.entries.description')), '월세');
    await fillTwoLines(user, t, '1e3', '1000');

    expect(screen.getByText(t('ledger.entries.amountInvalid'))).toBeDefined();
    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));
    expect(callsTo('POST', POST_ENTRY)).toHaveLength(0);

    const amounts = screen.getAllByLabelText(t('ledger.entries.amount'));
    await user.clear(amounts[0] as HTMLInputElement);
    await user.type(amounts[0] as HTMLInputElement, '1,000');

    expect(screen.queryByText(t('ledger.entries.amountInvalid'))).toBeNull();
    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));

    await waitFor(() => {
      expect(callsTo('POST', POST_ENTRY)).toHaveLength(1);
    });

    // The grouping separator is a typing convenience; what is sent is exact.
    const sent = callsTo('POST', POST_ENTRY)[0]?.body as { postings: { amount: string }[] };
    expect(sent.postings[0]?.amount).toBe('1000');
  });

  it('asks for the base-currency figure on a foreign line, and records the rate without applying it', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/new');

    await user.type(await screen.findByLabelText(t('ledger.entries.description')), '해외 송금');
    await fillTwoLines(user, t, '1000', '1000');

    const currencies = screen.getAllByLabelText(t('common.currency'));
    await user.selectOptions(currencies[0] as HTMLSelectElement, 'USD');

    // A line in another currency cannot balance on its own figure.
    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));
    expect(callsTo('POST', POST_ENTRY)).toHaveLength(0);
    expect(screen.getByText(t('ledger.entries.baseAmountRequired'))).toBeDefined();

    const rate = screen.getByLabelText(t('ledger.entries.rate'));
    // Nothing here suggests the rate will be fetched or multiplied out.
    expect(rate.getAttribute('title')).toBe(t('ledger.entries.rateHint'));
    await user.type(rate, '1355.5');
    await user.type(screen.getByLabelText(t('ledger.entries.baseAmount')), '1000');

    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));
    await waitFor(() => {
      expect(callsTo('POST', POST_ENTRY)).toHaveLength(1);
    });

    const sent = callsTo('POST', POST_ENTRY)[0]?.body as {
      postings: { amount: string; baseAmount?: string; currencyCode?: string; rate?: string }[];
    };
    expect(sent.postings[0]).toMatchObject({
      amount: '1000',
      baseAmount: '1000',
      currencyCode: 'USD',
      rate: '1355.5',
    });
  });

  it('sends an idempotency key, so a retried save cannot double-post', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/entries/new');

    await user.type(await screen.findByLabelText(t('ledger.entries.description')), '월세');
    await fillTwoLines(user, t, '1000', '1000');
    await user.click(screen.getByRole('button', { name: t('ledger.entries.save') }));

    await waitFor(() => {
      expect(callsTo('POST', POST_ENTRY)).toHaveLength(1);
    });
    expect(callsTo('POST', POST_ENTRY)[0]?.headers['idempotency-key']).toMatch(/[0-9a-f-]{36}/);
  });
});
