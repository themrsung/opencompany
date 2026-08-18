import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { json, mountAccounting, route, seedBook } from './harness.js';

describe('the chart of accounts', () => {
  beforeEach(seedBook);

  it('shows that an account with children has stopped accepting postings', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/accounts');

    const parentRow = (await screen.findByText('유동자산')).closest('tr');
    expect(parentRow).not.toBeNull();
    expect(within(parentRow as HTMLElement).getByText(t('ledger.accounts.notPostable'))).toBeDefined();

    await user.click(parentRow as HTMLElement);

    // Said in the reader's language, before anyone tries to post to it.
    expect(screen.getByText(t('ledger.accounts.refusalSubtotal'))).toBeDefined();
  });

  it('derives the type from the code prefix and refuses a prefix that has none', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/accounts');

    await user.click(await screen.findByRole('button', { name: t('ledger.accounts.open') }));
    const code = screen.getByLabelText(t('ledger.accounts.idLabel'));

    await user.type(code, '2100');
    expect(
      screen.getByText(
        t('ledger.accounts.typeOfPrefix', { prefix: '2', type: t('ledger.accounts.type.LIABILITY') }),
      ),
    ).toBeDefined();
    expect(screen.getByText(t('ledger.accounts.typeFixed'))).toBeDefined();

    await user.clear(code);
    await user.type(code, '9100');
    expect(screen.getByText(t('ledger.accounts.idInvalid'))).toBeDefined();
  });

  it('warns that a postable parent is about to become a subtotal', async () => {
    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/accounts');

    await user.click(await screen.findByRole('button', { name: t('ledger.accounts.open') }));
    await user.selectOptions(screen.getByLabelText(t('ledger.accounts.parent')), '4100');

    expect(screen.getByText(t('ledger.accounts.parentBecomesSubtotal', { name: '매출' }))).toBeDefined();
  });

  it('hides a retired account from the list without touching what it holds', async () => {
    route('GET /accounting/books/b-1/accounts', () =>
      json({
        items: [
          { id: '1100', nameKo: '현금', type: 'ASSET', postable: true, retired: false, contra: false },
          { id: '1900', nameKo: '옛 계정', type: 'ASSET', postable: false, retired: true, contra: false },
        ],
        nextCursor: null,
      }),
    );

    const user = userEvent.setup();
    const { t } = mountAccounting('/accounting/accounts');

    expect(await screen.findByText('현금')).toBeDefined();
    expect(screen.queryByText('옛 계정')).toBeNull();

    await user.click(screen.getByLabelText(t('ledger.accounts.includeRetired')));

    const retiredRow = (await screen.findByText('옛 계정')).closest('tr');
    expect(within(retiredRow as HTMLElement).getByText(t('ledger.accounts.retired'))).toBeDefined();
  });
});
