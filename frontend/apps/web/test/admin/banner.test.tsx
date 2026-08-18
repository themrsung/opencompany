// Harness first — see fetchMock.ts.
import { renderScreen, setRoutes, callsTo, SOMEONE } from '../signin/harness.js';

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { AppChrome } from '../../src/session/AppChrome.js';

const LIVE = {
  grantId: 'grant-9',
  engineerName: 'Dana Okafor',
  capabilities: ['hr.employee:read', 'accounting.entry:read'],
  expiresAt: '2026-08-18T27:00:00.000',
  revocableByYou: true,
};

describe('the live support banner', () => {
  beforeEach(() => {
    setRoutes({
      'GET /auth/session/active': { body: [] },
      'GET /support/session/live': { body: [LIVE] },
    });
  });

  it('names who is connected, what is open and when it ends, to everyone', async () => {
    renderScreen(<AppChrome />, { identity: SOMEONE });

    const banner = await screen.findByText(/Dana Okafor is connected for remote support/);

    expect(banner.textContent).toContain('hr.employee:read');
    expect(banner.textContent).toContain('accounting.entry:read');
    // The business instant reads past midnight rather than being "corrected".
    expect(banner.textContent).toContain('2026-08-18 27:00');
  });

  it('cannot be dismissed', async () => {
    const { container } = renderScreen(<AppChrome />, { identity: SOMEONE });

    await screen.findByText(/Dana Okafor is connected/);

    expect(container.querySelector('.ci-banner__dismiss')).toBeNull();
    expect(screen.queryByRole('button', { name: /dismiss|close|hide/i })).toBeNull();
  });

  it('offers Revoke now to someone who may use it, and revokes on the spot', async () => {
    const user = userEvent.setup();
    renderScreen(<AppChrome />, { identity: SOMEONE });

    const revoke = await screen.findByRole('button', { name: 'Revoke now' });
    await user.click(revoke);

    await waitFor(() => {
      expect(callsTo('POST /support/temporary-master/grant-9/revoke').length).toBe(1);
    });
  });

  it('still shows the banner to someone who may not revoke, without the button', async () => {
    setRoutes({
      'GET /auth/session/active': { body: [] },
      'GET /support/session/live': { body: [{ ...LIVE, revocableByYou: false }] },
    });
    renderScreen(<AppChrome />, { identity: SOMEONE });

    await screen.findByText(/Dana Okafor is connected/);

    expect(screen.queryByRole('button', { name: 'Revoke now' })).toBeNull();
  });

  it('shows nothing at all when no session is live', async () => {
    setRoutes({
      'GET /auth/session/active': { body: [] },
      'GET /support/session/live': { body: [] },
    });
    renderScreen(<AppChrome />, { identity: SOMEONE });

    await screen.findByText(/Signed in as/);

    expect(screen.queryByText(/is connected for remote support/)).toBeNull();
  });
});
