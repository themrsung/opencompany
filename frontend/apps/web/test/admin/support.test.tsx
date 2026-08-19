// Harness first — see fetchMock.ts.
import { renderScreen, setRoutes, callsTo, ONE_COMPANY, SOMEONE } from '../signin/harness.js';

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { SupportScreen } from '../../src/screens/admin/SupportScreen.js';

const LEGAL_NAME = '주식회사 코어인트라';

const BASE = {
  'GET /auth/session/active': { body: [] },
  'GET /support/session/live': { body: [] },
  'GET /org/companies': { body: ONE_COMPANY },
  'GET /support/temporary-master/capabilities': {
    body: [
      {
        key: 'hr.compensation:read',
        description: 'Read every employee’s salary history',
      },
      { key: 'accounting.entry:read', description: 'Read every posted journal entry' },
      { key: 'mystery:thing', description: '' },
    ],
  },
};

async function walkToConfirmation(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const boxes = await screen.findAllByRole('checkbox');
  await user.click(boxes[0] as HTMLInputElement);
  await user.click(screen.getByRole('button', { name: 'Next' }));

  await user.type(screen.getByLabelText('Support engineer'), 'Dana Okafor');
  await user.type(screen.getByLabelText('Account to use'), 'support-1');
  await user.type(
    screen.getByLabelText('Reason for issuing'),
    'Payroll export is failing on the月 close and needs a look',
  );
  await user.click(screen.getByRole('button', { name: 'Next' }));

  await user.type(screen.getByLabelText('Approval document'), 'doc-77');
  await user.type(screen.getByLabelText('Name'), '이대표');
  await user.type(screen.getByLabelText('Account'), 'rep-1');
  await user.click(screen.getByRole('button', { name: 'Next' }));
}

describe('issuing a temporary master account', () => {
  beforeEach(() => {
    setRoutes({ ...BASE });
  });

  it('starts with every capability off and offers no way to tick them all', async () => {
    renderScreen(<SupportScreen />, { identity: SOMEONE });

    const boxes = await screen.findAllByRole('checkbox');

    expect(boxes.length).toBe(3);
    for (const box of boxes) {
      expect((box as HTMLInputElement).checked).toBe(false);
    }
    expect(screen.queryByRole('button', { name: /all|everything/i })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: /all|everything/i })).toBeNull();
    expect(screen.getByText('There is no button that grants everything at once')).toBeDefined();
    expect(screen.getByText('Nothing is ticked yet.')).toBeDefined();
  });

  it('says in end-user terms what each capability exposes', async () => {
    renderScreen(<SupportScreen />, { identity: SOMEONE });

    expect(await screen.findByText(/Read every employee’s salary history/)).toBeDefined();
    expect(screen.getByText(/Read every posted journal entry/)).toBeDefined();
  });

  it('refuses to offer a capability the server cannot describe', async () => {
    renderScreen(<SupportScreen />, { identity: SOMEONE });

    const boxes = await screen.findAllByRole('checkbox');

    expect((boxes[2] as HTMLInputElement).disabled).toBe(true);
    expect(
      screen.getByText(/The server carries no wording for what this opens/),
    ).toBeDefined();
  });

  it('will not issue until the company name is typed exactly', async () => {
    const user = userEvent.setup();
    setRoutes({
      ...BASE,
      'POST /support/temporary-master': {
        body: { grantId: 'grant-1', expiresAt: '2026-08-18T20:00:00.000', capabilities: [] },
      },
    });
    renderScreen(<SupportScreen />, { identity: SOMEONE });

    await walkToConfirmation(user);

    const confirm = screen.getByLabelText(`Type the company name "${LEGAL_NAME}" to issue this`);
    const issue = screen.getByRole('button', { name: 'Issue' });
    expect((issue as HTMLButtonElement).disabled).toBe(true);

    await user.type(confirm, '주식회사 코어인트라 ');
    expect((issue as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('That is not the company name, exactly.')).toBeDefined();

    await user.clear(confirm);
    await user.type(confirm, LEGAL_NAME);
    expect((issue as HTMLButtonElement).disabled).toBe(false);

    await user.click(issue);

    await waitFor(() => {
      expect(callsTo('POST /support/temporary-master').length).toBe(1);
    });
    const [call] = callsTo('POST /support/temporary-master');
    const body = call?.body as Record<string, unknown>;
    expect(body['typedCompanyName']).toBe(LEGAL_NAME);
    expect(body['capabilities']).toEqual(['hr.compensation:read']);
    expect(body['hours']).toBe(4);
    expect(call?.headers['idempotency-key']).toBeDefined();
  });

  it('defaults to four hours, caps at twenty-four and offers no extension', async () => {
    const user = userEvent.setup();
    renderScreen(<SupportScreen />, { identity: SOMEONE });

    const boxes = await screen.findAllByRole('checkbox');
    await user.click(boxes[0] as HTMLInputElement);
    await user.click(screen.getByRole('button', { name: 'Next' }));

    const window = screen.getByLabelText('Time limit (hours)') as HTMLInputElement;
    expect(window.value).toBe('4');
    expect(window.getAttribute('max')).toBe('24');

    await user.clear(window);
    await user.type(window, '48');
    expect(window.value).toBe('24');

    expect(screen.queryByRole('button', { name: /extend/i })).toBeNull();
    expect(
      screen.getByText('Maximum 24 hours, and it cannot be extended. Issue a new one instead'),
    ).toBeDefined();
  });
});
