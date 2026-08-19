// Harness first — see fetchMock.ts.
import { renderScreen, setRoutes, ONE_COMPANY, SOMEONE } from '../signin/harness.js';

import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuditScreen } from '../../src/screens/admin/AuditScreen.js';

/**
 * A shift that ran to three in the morning. The business day is the 30th and
 * the clock reads 27:00; the row was written at 02:00 UTC on the 31st. Both
 * are true, and the pair is the point.
 */
const NIGHT_SHIFT_ENTRY = {
  id: 'entry-1',
  action: 'post',
  actorAccountId: 'acc-1',
  actorDisplayName: '김지원',
  actorKind: 'USER',
  businessInstant: '2026-08-30T27:00:00.000',
  capability: 'accounting.entry:post',
  outcome: 'ALLOWED',
  recordedAt: '2026-08-31T02:00:00Z',
  requestId: 'req-42',
  resource: 'accounting.entry',
  resourceId: 'entry-1',
  rowsTouched: 2,
};

const BASE = {
  'GET /auth/session/active': { body: [] },
  'GET /org/companies': { body: ONE_COMPANY },
  'GET /audit/retention': { body: 365 },
  'GET /audit/trail': { body: [NIGHT_SHIFT_ENTRY] },
};

async function loadTrail(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.type(await screen.findByLabelText('Resource'), 'accounting.entry');
  await user.type(screen.getByLabelText('Resource id'), 'entry-1');
  await user.click(screen.getByRole('button', { name: 'Apply filters' }));
}

describe('the audit log', () => {
  beforeEach(() => {
    setRoutes({ ...BASE });
  });

  it('shows business time and UTC side by side, never collapsed', async () => {
    const user = userEvent.setup();
    renderScreen(<AuditScreen />, { identity: SOMEONE });

    await loadTrail(user);

    expect(await screen.findByText('Business time')).toBeDefined();
    expect(screen.getByText('Recorded (UTC)')).toBeDefined();

    // The business instant keeps its 27:00 rather than rolling into the 31st.
    expect(screen.getByText(/2026-08-30\s+27:00/)).toBeDefined();
    // And the UTC instant is shown exactly as stored, not converted.
    expect(screen.getByText('2026-08-31T02:00:00Z')).toBeDefined();
  });

  it('puts the two clocks in different cells of the same row', async () => {
    const user = userEvent.setup();
    const { container } = renderScreen(<AuditScreen />, { identity: SOMEONE });

    await loadTrail(user);
    await screen.findByText('2026-08-31T02:00:00Z');

    const cells = [...container.querySelectorAll('tbody tr:first-child td')].map(
      (cell) => cell.textContent ?? '',
    );
    expect(cells[0]).toMatch(/2026-08-30\s+27:00/);
    expect(cells[1]).toBe('2026-08-31T02:00:00Z');
  });

  it('lets retention be raised and not lowered', async () => {
    const user = userEvent.setup();
    renderScreen(<AuditScreen />, { identity: SOMEONE });

    expect(await screen.findByText('365 days')).toBeDefined();

    const field = screen.getByLabelText('New retention (days)');
    await user.type(field, '30');

    expect(screen.getByText('Only a longer period than the current one can be set.')).toBeDefined();
    expect((screen.getByRole('button', { name: 'Raise retention' }) as HTMLButtonElement).disabled).toBe(
      true,
    );

    await user.clear(field);
    await user.type(field, '400');

    expect((screen.getByRole('button', { name: 'Raise retention' }) as HTMLButtonElement).disabled).toBe(
      false,
    );
  });
});
