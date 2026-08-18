// The harness first: it installs the fetch dispatcher before the API client
// singleton binds `globalThis.fetch`.
import { renderScreen, setRoutes, callsTo } from './harness.js';

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { SignInScreen } from '../../src/screens/signin/SignInScreen.js';

const NOT_SIGNED_IN = {
  'GET /auth/session/active': { status: 401, body: { status: 401, code: 'unauthenticated' } },
  'POST /auth/session/refresh': { status: 401, body: { status: 401, code: 'unauthenticated' } },
};

describe('signing in', () => {
  beforeEach(() => {
    setRoutes({ ...NOT_SIGNED_IN });
  });

  it('has no password field anywhere on it', async () => {
    const { container } = renderScreen(<SignInScreen />);

    await screen.findByLabelText('Username');

    expect(container.querySelectorAll('input[type="password"]').length).toBe(0);
    expect(screen.queryByLabelText(/password/i)).toBeNull();
    // The two things it does ask for, and nothing else that takes a secret.
    expect(screen.getByLabelText('Username')).toBeDefined();
    expect(screen.getByLabelText('Authentication code')).toBeDefined();
    // And it says so, rather than looking like a login form with a field missing.
    expect(screen.getByText('There are no passwords here')).toBeDefined();
  });

  it('offers a recovery code instead of the authenticator, still with no password', async () => {
    const user = userEvent.setup();
    const { container } = renderScreen(<SignInScreen />);

    await user.click(await screen.findByRole('button', { name: 'Use a recovery code' }));

    expect(screen.getByLabelText('Recovery code')).toBeDefined();
    expect(container.querySelectorAll('input[type="password"]').length).toBe(0);
  });

  it('says the same thing whether the username or the code was wrong', async () => {
    const user = userEvent.setup();
    setRoutes({
      ...NOT_SIGNED_IN,
      'POST /auth/session': { status: 401, body: { status: 401, code: 'unauthenticated' } },
    });
    renderScreen(<SignInScreen />);

    await user.type(await screen.findByLabelText('Username'), 'nobody');
    await user.type(screen.getByLabelText('Authentication code'), '000000');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('That username or code is not right.');
    // One request, whichever was wrong: nothing here that a stopwatch could read.
    expect(callsTo('POST /auth/session').length).toBe(1);
  });

  it('shows how long the lockout has to run and will not retry into it', async () => {
    const user = userEvent.setup();
    setRoutes({
      ...NOT_SIGNED_IN,
      'POST /auth/session': {
        status: 429,
        body: { status: 429, code: 'rate_limited' },
        headers: { 'retry-after': '90' },
      },
    });
    renderScreen(<SignInScreen />);

    await user.type(await screen.findByLabelText('Username'), 'someone');
    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(screen.getByText('Sign-in is paused')).toBeDefined();
    });
    expect(screen.getByText('You can try again in 90 seconds.')).toBeDefined();

    const submit = screen.getByRole('button', { name: /Try again in 90s/ });
    expect((submit as HTMLButtonElement).disabled).toBe(true);

    await user.click(submit);
    expect(callsTo('POST /auth/session').length).toBe(1);
  });

  it('keeps nothing token-shaped in local storage on the way in', async () => {
    const user = userEvent.setup();
    setRoutes({
      ...NOT_SIGNED_IN,
      'POST /auth/session': {
        body: {
          accountId: 'acc-1',
          displayName: '김지원',
          employeeId: 'emp-1',
          locale: 'ko',
          master: true,
          remainingRecoveryCodes: 8,
        },
      },
    });
    renderScreen(<SignInScreen />);

    await user.type(await screen.findByLabelText('Username'), 'jiwon');
    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(callsTo('POST /auth/session').length).toBe(1);
    });

    const stored = JSON.stringify(globalThis.localStorage.getItem('coreintra.profile'));
    expect(stored).not.toMatch(/token|jwt|bearer|secret/i);
  });
});
