import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { mountAccounting, seedBook } from './harness.js';

describe('the accounting area', () => {
  beforeEach(seedBook);

  it('opens on the chart of accounts with the book named', async () => {
    const { t } = mountAccounting('/accounting/accounts');
    expect(await screen.findByText('현금')).toBeDefined();
    expect(screen.getAllByText(t('ledger.accounts.postable')).length).toBeGreaterThan(0);
  });
});
