// Harness first — see fetchMock.ts.
import { renderScreen, setRoutes, SOMEONE } from './harness.js';

import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { SecurityScreen } from '../../src/screens/signin/SecurityScreen.js';

const ENROLLED = {
  'GET /auth/session/active': { body: [] },
  'GET /account/enrolment': {
    body: { accountId: 'acc-1', emailOtpAvailable: false, remainingRecoveryCodes: 8 },
  },
};

describe('account security', () => {
  beforeEach(() => {
    setRoutes({ ...ENROLLED });
  });

  it('shows the recovery codes once and never again', async () => {
    const user = userEvent.setup();
    setRoutes({
      ...ENROLLED,
      'POST /account/enrolment': {
        body: {
          accountId: 'acc-1',
          otpauthUri: 'otpauth://totp/CoreIntra:jiwon?secret=JBSWY3DPEHPK3PXP',
          secretBase32: 'JBSWY3DPEHPK3PXP',
          recoveryCodes: ['AAAA-1111', 'BBBB-2222'],
        },
      },
    });
    renderScreen(<SecurityScreen />, { identity: SOMEONE });

    await user.click(await screen.findByRole('button', { name: 'Start enrolment' }));

    expect(await screen.findByText('AAAA-1111')).toBeDefined();
    expect(screen.getByText('BBBB-2222')).toBeDefined();
    expect(
      screen.getByText('Shown once, now. Leave this screen and you cannot see them again.'),
    ).toBeDefined();

    await user.click(screen.getByRole('button', { name: 'Copied — hide them' }));

    expect(screen.queryByText('AAAA-1111')).toBeNull();
    expect(screen.queryByText('BBBB-2222')).toBeNull();
    // Nothing on the screen offers to show them again.
    expect(screen.queryByRole('button', { name: /show|reveal|again/i })).toBeNull();
  });

  it('keeps the remaining count in view once the codes are gone', async () => {
    renderScreen(<SecurityScreen />, { identity: SOMEONE });

    expect(await screen.findByText('8 recovery codes left')).toBeDefined();
  });

  it('does not offer an email second factor when no mail server is configured', async () => {
    renderScreen(<SecurityScreen />, { identity: SOMEONE });

    await screen.findByText('8 recovery codes left');

    expect(screen.queryByText('Email second factor')).toBeNull();
  });

  it('shows the enrolment key rather than pretending to draw a QR it cannot', async () => {
    const user = userEvent.setup();
    setRoutes({
      ...ENROLLED,
      'POST /account/enrolment': {
        body: {
          accountId: 'acc-1',
          otpauthUri: 'otpauth://totp/CoreIntra:jiwon?secret=JBSWY3DPEHPK3PXP',
          secretBase32: 'JBSWY3DPEHPK3PXP',
          recoveryCodes: [],
        },
      },
    });
    renderScreen(<SecurityScreen />, { identity: SOMEONE });

    await user.click(await screen.findByRole('button', { name: 'Start enrolment' }));

    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeDefined();
  });
});
