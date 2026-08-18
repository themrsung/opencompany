// Harness first — see fetchMock.ts.
import { currentPath, renderScreen, setRoutes, SOMEONE } from './harness.js';

import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { AppChrome } from '../../src/session/AppChrome.js';

describe('the session guard', () => {
  beforeEach(() => {
    setRoutes({});
  });

  it('sends someone with no session to sign in rather than to an empty screen', async () => {
    setRoutes({
      'GET /auth/session/active': { status: 401, body: { status: 401, code: 'unauthenticated' } },
      'POST /auth/session/refresh': { status: 401, body: { status: 401, code: 'unauthenticated' } },
    });

    renderScreen(<AppChrome />, { path: '/org' });

    await waitFor(() => {
      expect(currentPath()).toBe('/signin');
    });
    // And never renders the shell on the way past.
    expect(screen.queryByText('Organisation')).toBeNull();
  });

  it('renders the shell once the session is confirmed', async () => {
    setRoutes({
      'GET /auth/session/active': { body: [] },
      'GET /support/session/live': { body: [] },
    });

    renderScreen(<AppChrome />, { identity: SOMEONE, path: '/org' });

    expect(await screen.findByText('Organisation')).toBeDefined();
    expect(currentPath()).toBe('/org');
  });
});
