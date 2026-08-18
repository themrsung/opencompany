// Harness first — see fetchMock.ts.
import { renderScreen, setRoutes, callsTo, ONE_COMPANY, SOMEONE } from '../signin/harness.js';

import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { PermissionExplainerScreen } from '../../src/screens/org/PermissionExplainerScreen.js';

const DENY_WON = {
  allowed: false,
  permission: 'hr.compensation:read',
  summary: 'hr.compensation:read is denied for acc-7 on 조민서',
  decidingGrant: {
    id: 'grant-deny',
    effect: 'DENY',
    permission: 'hr.compensation:read',
    scope: 'COMPANY',
    source: 'ACCOUNT',
    sourceLabel: 'Direct grant on acc-7',
    sourceId: 'acc-7',
  },
  considerations: [
    {
      applied: true,
      reason: 'An explicit deny on the account',
      grant: {
        id: 'grant-deny',
        effect: 'DENY',
        permission: 'hr.compensation:read',
        scope: 'COMPANY',
        source: 'ACCOUNT',
        sourceLabel: 'Direct grant on acc-7',
        sourceId: 'acc-7',
      },
    },
    {
      applied: false,
      reason: 'Beaten by the explicit deny',
      grant: {
        id: 'grant-allow',
        effect: 'ALLOW',
        permission: 'hr.compensation:read',
        scope: 'ORG_UNIT_SUBTREE',
        source: 'RANK',
        sourceLabel: '부장',
        sourceId: 'rank-3',
      },
    },
  ],
};

const BASE = {
  'GET /auth/session/active': { body: [] },
  'GET /org/companies': { body: ONE_COMPANY },
  'GET /permissions/effective': {
    body: { accountId: 'acc-7', asOf: '2026-08-18', grants: [], rankIds: ['rank-3'] },
  },
  'GET /permissions/decision/about-employee': { body: DENY_WON },
};

describe('the permission explainer', () => {
  beforeEach(() => {
    setRoutes({ ...BASE });
  });

  it('names the deny that won when the answer is no', async () => {
    const user = userEvent.setup();
    renderScreen(<PermissionExplainerScreen />, {
      identity: SOMEONE,
      path: '/org/explainer?employeeId=emp-9&asOf=2026-08-18',
    });

    await user.type(await screen.findByLabelText('Who (account)'), 'acc-7');
    await user.type(screen.getByLabelText('What (permission)'), 'hr.compensation:read');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    expect(await screen.findByText('No')).toBeDefined();
    expect(screen.getByText('This explicit deny won')).toBeDefined();
    expect(
      screen.getByText('An explicit deny beats every allow. No amount of granting opens it.'),
    ).toBeDefined();
    // The chain, not a verdict: the grant that decided, named and sourced.
    expect(screen.getAllByText('Direct grant on acc-7').length).toBeGreaterThan(0);
  });

  it('shows what was considered and passed over, with the reason', async () => {
    const user = userEvent.setup();
    renderScreen(<PermissionExplainerScreen />, {
      identity: SOMEONE,
      path: '/org/explainer?employeeId=emp-9',
    });

    await user.type(await screen.findByLabelText('Who (account)'), 'acc-7');
    await user.type(screen.getByLabelText('What (permission)'), 'hr.compensation:read');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    expect(await screen.findByText('Beaten by the explicit deny')).toBeDefined();
    expect(screen.getByText('Not applied')).toBeDefined();
    expect(screen.getByText('부장')).toBeDefined();
  });

  it('asks about a date, and sends the one it was given', async () => {
    const user = userEvent.setup();
    renderScreen(<PermissionExplainerScreen />, {
      identity: SOMEONE,
      path: '/org/explainer?employeeId=emp-9&asOf=2026-01-31',
    });

    const asOf = (await screen.findByLabelText('As of')) as HTMLInputElement;
    expect(asOf.value).toBe('2026-01-31');

    await user.type(screen.getByLabelText('Who (account)'), 'acc-7');
    await user.type(screen.getByLabelText('What (permission)'), 'hr.compensation:read');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    await screen.findByText('No');
    const [call] = callsTo('GET /permissions/decision/about-employee');
    expect(call?.query['asOf']).toBe('2026-01-31');
    expect(call?.query['employeeId']).toBe('emp-9');
  });
});
