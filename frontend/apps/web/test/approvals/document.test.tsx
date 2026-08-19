import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { requestsTo, resetApi, route } from '../support/api.js';
import { detailFor, EXPENSE, stubCompanies } from '../support/fixtures.js';
import { renderScreens } from '../support/render.js';

/**
 * One document's line and trail.
 *
 * 대결 and 전결 are the reason this screen has tests of its own. Both are ways
 * for a signature to end up somewhere other than where the line said it would,
 * and both are read years later by somebody trying to establish who agreed to
 * what. The trail has to be unambiguous about it on the screen, not only in the
 * database.
 */

const ACTED_FOR = detailFor(EXPENSE, {
  awaitingMe: false,
  lineSummary: '대결로 처리되었습니다',
  steps: [
    {
      id: 'step-1',
      position: 0,
      kind: 'APPROVE',
      state: 'COMPLETED',
      pending: false,
      satisfied: true,
      requiredApprovals: 1,
      approvalsGiven: 1,
      roleExpression: 'rank:bujang@DRAFTER_UNIT',
      approvers: [
        { accountId: 'acc-park', displayName: '박부장', rankLabel: '부장', acted: false },
      ],
    },
    {
      id: 'step-2',
      position: 1,
      kind: 'APPROVE',
      state: 'SKIPPED',
      pending: false,
      satisfied: false,
      requiredApprovals: 1,
      approvalsGiven: 0,
      roleExpression: 'representative:ANY',
      approvers: [
        { accountId: 'acc-rep', displayName: '김대표', rankLabel: '대표이사', acted: false },
      ],
    },
  ],
  trail: [
    {
      id: 'act-1',
      stepId: 'step-1',
      action: 'ACTING',
      actorAccountId: 'acc-kim',
      actorDisplayName: '김대리',
      onBehalfOfAccountId: 'acc-park',
      comment: '부장 부재로 대결합니다',
      actedAt: '2026-08-16T26:00:00.000',
      documentSnapshotHash: 'sha256:9f2c',
    },
    {
      id: 'act-2',
      stepId: 'step-1',
      action: 'DELEGATED_FINAL',
      actorAccountId: 'acc-lee',
      actorDisplayName: '이상무',
      comment: '전결 규정 제5조에 따릅니다',
      actedAt: '2026-08-16T27:30:00.000',
      documentSnapshotHash: 'sha256:9f2c',
    },
  ],
});

function trailRow(action: string): HTMLElement {
  const cell = screen.getByText(action);
  const row = cell.closest('tr');
  if (row === null) {
    throw new Error(`no trail row for ${action}`);
  }
  return row;
}

beforeEach(() => {
  resetApi();
});

afterEach(() => {
  resetApi();
});

describe('대결', () => {
  it('says who acted and, separately, whom they acted for', async () => {
    stubCompanies();
    route('GET /approvals/doc-expense', { body: ACTED_FOR });
    renderScreens('/approvals/doc-expense');

    await screen.findByText('결재 이력');
    const row = within(trailRow('대결'));

    expect(row.getByText('김대리')).toBeDefined();
    expect(row.getByText('박부장 님을 대신하여 결재했습니다')).toBeDefined();
  });

  it('never lets the absent approver look as though they signed', async () => {
    stubCompanies();
    route('GET /approvals/doc-expense', { body: ACTED_FOR });
    renderScreens('/approvals/doc-expense');

    await screen.findByText('결재 이력');

    // The step 박부장 was routed to still says, in its own row, that they have
    // not signed. Their name appears once as an approver who did not act and
    // once inside the sentence about being stood in for — never as an actor.
    const step = within(screen.getByText(/rank:bujang@DRAFTER_UNIT/).closest('tr') ?? document.body);
    expect(step.getByText(/박부장 \(부장\)/)).toBeDefined();
    expect(step.getByText('· 아직 결재하지 않았습니다')).toBeDefined();
  });
});

describe('전결', () => {
  it('marks the steps nobody signed as skipped, and says so in words', async () => {
    stubCompanies();
    route('GET /approvals/doc-expense', { body: ACTED_FOR });
    renderScreens('/approvals/doc-expense');

    await screen.findByText('결재 이력');
    const skipped = within(
      screen.getByText(/representative:ANY/).closest('tr') ?? document.body,
    );

    expect(skipped.getByText('건너뜀')).toBeDefined();
    expect(
      skipped.getByText('전결로 건너뛴 단계입니다. 이 단계에 서명한 사람은 없습니다'),
    ).toBeDefined();
  });
});

describe('the trail', () => {
  it('keeps the business time it was signed at, past midnight and all', async () => {
    stubCompanies();
    route('GET /approvals/doc-expense', { body: ACTED_FOR });
    renderScreens('/approvals/doc-expense');

    await screen.findByText('결재 이력');

    expect(screen.getByText('2026-08-16 26:00')).toBeDefined();
    expect(screen.getByText('2026-08-16 27:30')).toBeDefined();
  });

  it('shows the fingerprint each signature was taken against', async () => {
    stubCompanies();
    route('GET /approvals/doc-expense', { body: ACTED_FOR });
    renderScreens('/approvals/doc-expense');

    await screen.findByText('결재 이력');
    expect(screen.getAllByText('sha256:9f2c')).toHaveLength(2);
  });
});

describe('a decision that needs a reason', () => {
  it('asks for it before it will send anything', async () => {
    stubCompanies();
    route('GET /approvals/doc-expense', { body: detailFor(EXPENSE) });
    route('POST /approvals/doc-expense/steps/step-1/hold', { body: detailFor(EXPENSE) });
    const user = userEvent.setup();
    renderScreens('/approvals/doc-expense');

    await screen.findByText('결재 이력');
    await user.click(screen.getByRole('button', { name: '보류' }));

    const form = await screen.findByRole('form', { name: '보류 사유' });
    const send = within(form).getByRole('button', { name: '보류' });
    expect((send as HTMLButtonElement).disabled).toBe(true);

    await user.type(within(form).getByRole('textbox'), '예산 확정까지 기다립니다');
    await user.click(send);

    await waitFor(() => {
      expect(requestsTo('POST /approvals/doc-expense/steps/step-1/hold')).toHaveLength(1);
    });
    const sent = requestsTo('POST /approvals/doc-expense/steps/step-1/hold')[0]?.body as
      | { reason?: string }
      | undefined;
    expect(sent?.reason).toBe('예산 확정까지 기다립니다');
  });
});
