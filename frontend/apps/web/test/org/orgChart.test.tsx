// Harness first — see fetchMock.ts.
import { callsTo, renderScreen, setRoutes, ONE_COMPANY, SOMEONE } from '../signin/harness.js';

import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { OrgChartScreen } from '../../src/screens/org/OrgChartScreen.js';

const UNITS = {
  items: [
    { id: 'unit-1', nameKo: '경영지원본부', nameEn: 'Corporate Services', code: 'CS', depth: 0, path: '/cs' },
    { id: 'unit-2', nameKo: '회계팀', nameEn: 'Accounting', code: 'ACC', depth: 1, path: '/cs/acc' },
  ],
  nextCursor: null,
};

const RANKS = {
  items: [
    { id: 'rank-2', labelKo: '부장', labelEn: 'General manager', code: 'GM', seniority: 50 },
    { id: 'rank-1', labelKo: '사원', labelEn: 'Staff', code: 'ST', seniority: 10 },
    { id: 'rank-3', labelKo: '대표', labelEn: 'Representative', code: 'CEO', seniority: 90, representative: true },
  ],
  nextCursor: null,
};

const EMPLOYEES = {
  items: [{ id: 'emp-1', nameKo: '조민서', nameEn: 'Minseo Cho', employeeNumber: '2019-014' }],
  nextCursor: null,
};

const POSITIONS = {
  items: [
    {
      id: 'pos-1',
      employeeId: 'emp-1',
      orgUnitId: 'unit-1',
      rankId: 'rank-2',
      primary: true,
      effectiveFrom: '2024-03-01',
    },
  ],
  nextCursor: null,
};

const BASE = {
  'GET /auth/session/active': { body: [] },
  'GET /org/companies': { body: ONE_COMPANY },
  'GET /org/companies/co-1': { body: ONE_COMPANY.items[0] },
  'GET /org/companies/co-1/ranks': { body: RANKS },
  'GET /org/companies/co-1/job-functions': { body: { items: [], nextCursor: null } },
  'GET /org/units': { body: UNITS },
  'GET /org/units/unit-1/positions': { body: POSITIONS },
  'GET /org/employees': { body: EMPLOYEES },
};

describe('the org chart', () => {
  beforeEach(() => {
    setRoutes({ ...BASE });
  });

  it('opens a unit and shows who held a position in it', async () => {
    const user = userEvent.setup();
    renderScreen(<OrgChartScreen />, { identity: SOMEONE });

    await user.click(await screen.findByText('Corporate Services'));

    expect(await screen.findByText('Minseo Cho')).toBeDefined();
    // Once in the roster row, once in the ladder below it.
    expect(screen.getAllByText('General manager').length).toBe(2);
  });

  it('asks the server about the date in the header, not about today', async () => {
    renderScreen(<OrgChartScreen />, { identity: SOMEONE });

    await screen.findByText('Corporate Services');

    fireEvent.change(screen.getByLabelText('As of'), { target: { value: '2026-01-31' } });

    await waitFor(() => {
      const dates = callsTo('GET /org/units').map((call) => call.query['businessDate']);
      expect(dates).toContain('2026-01-31');
    });
  });

  it('lists the ladder most senior first and never lets a row be edited on its own', async () => {
    const { container } = renderScreen(<OrgChartScreen />, { identity: SOMEONE });

    await screen.findAllByText('General manager');

    const ranks = rankTable(container);
    expect(rungs(ranks)).toEqual(['Representative', 'General manager', 'Staff']);

    // Nothing in a row edits a seniority: no field to type a number into.
    expect(ranks?.querySelectorAll('input').length).toBe(0);
  });

  it('saves a reordered ladder as one call over the whole thing', async () => {
    const user = userEvent.setup();
    setRoutes({ ...BASE, 'PUT /org/companies/co-1/ranks/order': { body: RANKS } });
    const { container } = renderScreen(<OrgChartScreen />, { identity: SOMEONE });

    await screen.findAllByText('General manager');

    // Move 사원 above 부장.
    const staffRow = [...(rankTable(container)?.querySelectorAll('tbody tr') ?? [])].find((row) =>
      row.textContent?.startsWith('Staff'),
    );
    const up = staffRow?.querySelector('button');
    expect(up).toBeDefined();
    await user.click(up as HTMLButtonElement);

    expect(rungs(rankTable(container))).toEqual(['Representative', 'Staff', 'General manager']);

    await user.click(screen.getByRole('button', { name: 'Save the order' }));

    await waitFor(() => {
      expect(callsTo('PUT /org/companies/co-1/ranks/order').length).toBe(1);
    });
    const [call] = callsTo('PUT /org/companies/co-1/ranks/order');
    const sent = call?.body as { rankIdsMostSeniorFirst?: string[] } | undefined;
    // The whole ladder, in one request, most senior first.
    expect(sent?.rankIdsMostSeniorFirst).toEqual(['rank-3', 'rank-1', 'rank-2']);
  });
});

function rankTable(container: HTMLElement): HTMLTableElement | undefined {
  return [...container.querySelectorAll('table')].find(
    (table) => table.querySelector('caption')?.textContent === 'Ranks',
  );
}

function rungs(table: HTMLTableElement | undefined): (string | null)[] {
  return [...(table?.querySelectorAll('tbody tr td:first-child') ?? [])].map(
    (cell) => cell.textContent,
  );
}
