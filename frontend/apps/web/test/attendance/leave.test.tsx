import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { page, resetApi, route } from '../support/api.js';
import { EMPLOYEES, stubCompanies } from '../support/fixtures.js';
import { renderScreens } from '../support/render.js';

/**
 * Leave: the balance, the ledger behind it, and the rules in force.
 *
 * The ledger is the point. §5 makes accrual configuration rather than statute,
 * so a screen that showed only "11.5 days" would be asking people to trust an
 * arithmetic they cannot see.
 */

const LEDGER = {
  employeeId: 'emp-1',
  policyId: 'pol-annual',
  asOf: '2026-08-18',
  balanceDays: '11.5',
  transactions: [
    {
      id: 'tx-1',
      kind: 'GRANT',
      days: '15.0',
      signedDays: '15.0',
      effectiveFrom: '2026-01-01',
      expiresOn: '2026-12-31',
      occurredAt: '2026-01-01T09:00:00.000',
    },
    {
      id: 'tx-2',
      kind: 'USE',
      days: '3.5',
      signedDays: '-3.5',
      occurredAt: '2026-06-02T27:00:00.000',
      sourceDocumentId: 'doc-leave-1',
    },
  ],
};

const RULES = {
  id: 'rules-3',
  companyId: 'co-1',
  version: 3,
  effectiveFrom: '2026-01-01',
  approvedUnderMode: '공동대표 (2 of 3)',
  approvalDocumentId: 'doc-rules-3',
  sections: [
    {
      number: '제42조',
      headingKo: '연차 유급휴가',
      headingEn: 'Annual paid leave',
      bodyKo: '입사 1년 미만인 사원에게는 1개월 개근 시 1일의 유급휴가를 부여합니다.',
      bodyEn: 'One day per full month worked in the first year.',
    },
  ],
};

function stubLeave(): void {
  stubCompanies();
  route('GET /org/employees', { body: page(EMPLOYEES) });
  route('GET /employment-rules', { body: RULES });
  route('GET /leave/balance', {
    body: { employeeId: 'emp-1', policyId: 'pol-annual', asOf: '2026-08-18', balanceDays: '11.5' },
  });
  route('GET /leave/ledger', { body: LEDGER });
  route('GET /leave/expiring', {
    body: { employeeId: 'emp-1', policyId: 'pol-annual', by: '2026-11-16', grants: [] },
  });
}

async function choosePerson(): Promise<void> {
  const user = userEvent.setup();
  // The roster arrives after the first paint; the option has to exist before
  // anyone can pick it.
  await screen.findByRole('option', { name: '김주간' });
  await user.selectOptions(screen.getByLabelText('사원'), 'emp-1');
  await user.type(screen.getByLabelText('휴가 정책 번호'), 'pol-annual');
}

beforeEach(() => {
  resetApi();
});

afterEach(() => {
  resetApi();
});

describe('before a policy is known', () => {
  it('says what it is waiting for rather than showing an empty balance', async () => {
    stubLeave();
    renderScreens('/leave');

    expect(await screen.findByText('사원을 선택해 주십시오')).toBeDefined();

    const user = userEvent.setup();
    await screen.findByRole('option', { name: '김주간' });
    await user.selectOptions(screen.getByLabelText('사원'), 'emp-1');

    expect(
      await screen.findByText('휴가 정책 번호를 입력하시면 잔여 연차와 원장을 보여 드립니다'),
    ).toBeDefined();
    // And it admits why the field is there at all.
    expect(
      screen.getByText('정책 목록을 돌려주는 API가 없어서, 지금은 정책 번호를 직접 입력해야 합니다'),
    ).toBeDefined();
  });
});

describe('the ledger', () => {
  it('shows the balance and every movement behind it', async () => {
    stubLeave();
    renderScreens('/leave');
    await choosePerson();

    expect(await screen.findByText('11.50')).toBeDefined();

    const ledger = within(screen.getByRole('table', { name: '연차 원장' }));
    expect(ledger.getByText('부여')).toBeDefined();
    expect(ledger.getByText('사용')).toBeDefined();
    // The direction comes from the server's signed column, not from this screen.
    expect(ledger.getByText('+15.00')).toBeDefined();
    expect(ledger.getByText('-3.50')).toBeDefined();
    // A leave taken during a shift that ran past midnight keeps its business day.
    expect(ledger.getByText('2026-06-02 27:00')).toBeDefined();
  });
});

describe('the accrual policy in force', () => {
  it('is shown as the 취업규칙 version it comes from, with the mode that enacted it', async () => {
    stubLeave();
    renderScreens('/leave');

    expect(await screen.findByText('연차 유급휴가')).toBeDefined();
    expect(
      screen.getByText(/제3판 · 2026-01-01 부터 적용합니다 · 공동대표 \(2 of 3\)로 승인되었습니다/),
    ).toBeDefined();
  });
});
