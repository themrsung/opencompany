import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { problem, requestsTo, resetApi, route } from '../support/api.js';
import {
  detailFor,
  EXPENSE,
  INBOX,
  JOINT,
  jointDetail,
  MINE,
  stubInbox,
} from '../support/fixtures.js';
import { renderScreens } from '../support/render.js';

/**
 * The 결재함, exercised the way somebody clearing it uses it.
 *
 * Everything below goes through the real `ApiClient` against a stubbed `fetch`,
 * so the assertions about the idempotency header are assertions about the
 * bytes that would reach the server.
 */

async function rowFor(title: string): Promise<HTMLTableRowElement> {
  const cell = await screen.findByText(title);
  const row = cell.closest('tr');
  if (row === null) {
    throw new Error(`no row for ${title}`);
  }
  return row;
}

function tab(name: string): HTMLElement {
  return screen.getByRole('tab', { name: new RegExp(name) });
}

beforeEach(() => {
  resetApi();
});

afterEach(() => {
  resetApi();
});

describe('the three buckets', () => {
  it('shows every document once, and the count on each tab is the number of rows under it', async () => {
    stubInbox();
    renderScreens('/');

    // 출장비 is both drafted by this account and waiting on it, and the server
    // returns it in both lists. It belongs under the one that asks something.
    expect(await screen.findByText('출장비 지출결의서')).toBeDefined();
    expect(tab('내 결재 대기').textContent).toContain('2');
    expect(within(screen.getByRole('tabpanel')).getAllByRole('row')).toHaveLength(3); // header + 2

    const user = userEvent.setup();
    await user.click(tab('내가 상신함'));

    expect(tab('내가 상신함').textContent).toContain('1');
    expect(await screen.findByText('노트북 구매 품의서')).toBeDefined();
    expect(screen.queryByText('출장비 지출결의서')).toBeNull();

    await user.click(tab('참조'));
    expect(tab('참조').textContent).toContain('1');
    expect(await screen.findByText('3분기 영업 보고')).toBeDefined();
  });

  it('asks for the whole inbox once rather than once per bucket', async () => {
    stubInbox();
    renderScreens('/');
    await screen.findByText('출장비 지출결의서');

    const user = userEvent.setup();
    await user.click(tab('내가 상신함'));
    await user.click(tab('참조'));

    expect(requestsTo('GET /approvals/inbox')).toHaveLength(1);
  });
});

describe('the partial approval of a 공동대표 document', () => {
  it('reads as its own state, not as one still in progress', async () => {
    stubInbox(INBOX, ['doc-joint', jointDetail()]);
    renderScreens('/');

    const joint = await rowFor('취업규칙 개정안');
    const expense = await rowFor('출장비 지출결의서');

    expect(within(joint).getByText('일부 승인')).toBeDefined();
    expect(within(expense).getByText('진행 중')).toBeDefined();
    expect(within(joint).queryByText('진행 중')).toBeNull();

    // And it says what it is waiting for, without anyone opening it.
    expect(within(joint).getByText('공동대표 정족수를 아직 채우지 못했습니다')).toBeDefined();
  });

  it('shows the quorum on the step once the row is read', async () => {
    stubInbox(INBOX, ['doc-joint', jointDetail()]);
    renderScreens('/');

    const user = userEvent.setup();
    await user.click(await rowFor('취업규칙 개정안'));

    expect(await screen.findByText('결재 · 공동대표 정족수 1/2')).toBeDefined();
  });
});

describe('the keyboard', () => {
  it('moves with J and K, opens with Enter and approves with A', async () => {
    stubInbox(INBOX, ['doc-expense', detailFor(EXPENSE)], ['doc-joint', jointDetail()]);
    route('POST /approvals/doc-joint/steps/step-rep/approve', { body: jointDetail() });
    const user = userEvent.setup();
    renderScreens('/');

    await screen.findByText('출장비 지출결의서');
    (await rowFor('출장비 지출결의서')).focus();

    await user.keyboard('j');
    expect(document.activeElement).toBe(await rowFor('취업규칙 개정안'));

    await user.keyboard('k');
    expect(document.activeElement).toBe(await rowFor('출장비 지출결의서'));

    await user.keyboard('j');
    await user.keyboard('a');

    await waitFor(() => {
      expect(requestsTo('POST /approvals/doc-joint/steps/step-rep/approve')).toHaveLength(1);
    });

    // The step is read from the document, not guessed from the list row, and
    // the moment it was signed at is a business instant on the wire.
    const approval = requestsTo('POST /approvals/doc-joint/steps/step-rep/approve')[0]?.body as
      | { actedAt?: string }
      | undefined;
    expect(approval?.actedAt).toMatch(/^\d{4}-\d{2}-\d{2}T-?\d{2}:\d{2}:\d{2}\.\d{3}$/);
  });

  it('opens the document with Enter', async () => {
    stubInbox(INBOX, ['doc-expense', detailFor(EXPENSE)]);
    const user = userEvent.setup();
    renderScreens('/');

    await screen.findByText('출장비 지출결의서');
    (await rowFor('출장비 지출결의서')).focus();
    await user.keyboard('{Enter}');

    expect(await screen.findByText('결재 이력')).toBeDefined();
  });

  it('leaves letters alone while somebody is typing a reason', async () => {
    stubInbox(INBOX, ['doc-expense', detailFor(EXPENSE)]);
    const user = userEvent.setup();
    renderScreens('/');

    await screen.findByText('출장비 지출결의서');
    (await rowFor('출장비 지출결의서')).focus();
    await user.keyboard('r');

    const form = await screen.findByRole('form', { name: '반려 사유' });
    const field = within(form).getByRole('textbox');
    await user.type(field, 'ajr');

    expect((field as HTMLInputElement).value).toBe('ajr');
    expect(requestsTo('POST /approvals/doc-expense/steps/step-1/approve')).toHaveLength(0);
  });
});

describe('반려', () => {
  it('cannot be sent without a reason, and sends the reason that was typed', async () => {
    stubInbox(INBOX, ['doc-expense', detailFor(EXPENSE)]);
    route('POST /approvals/doc-expense/steps/step-1/return', { body: detailFor(EXPENSE) });
    const user = userEvent.setup();
    renderScreens('/');

    await screen.findByText('출장비 지출결의서');
    (await rowFor('출장비 지출결의서')).focus();
    await user.keyboard('r');

    const form = await screen.findByRole('form', { name: '반려 사유' });
    const send = within(form).getByRole('button', { name: '반려' });
    expect((send as HTMLButtonElement).disabled).toBe(true);

    await user.click(send);
    expect(requestsTo('POST /approvals/doc-expense/steps/step-1/return')).toHaveLength(0);

    await user.type(within(form).getByRole('textbox'), '영수증이 빠졌습니다');
    expect((send as HTMLButtonElement).disabled).toBe(false);
    await user.click(send);

    await waitFor(() => {
      expect(requestsTo('POST /approvals/doc-expense/steps/step-1/return')).toHaveLength(1);
    });
    const sent = requestsTo('POST /approvals/doc-expense/steps/step-1/return')[0]?.body as
      | { reason?: string }
      | undefined;
    expect(sent?.reason).toBe('영수증이 빠졌습니다');
  });
});

describe('the idempotency key', () => {
  it('is reused when the same approval is retried, and is new for a different document', async () => {
    stubInbox(INBOX, ['doc-expense', detailFor(EXPENSE)], ['doc-joint', jointDetail()]);
    route(
      'POST /approvals/doc-expense/steps/step-1/approve',
      problem(503, 'server_error', '일시적인 오류입니다'),
      { body: detailFor(EXPENSE) },
    );
    route('POST /approvals/doc-joint/steps/step-rep/approve', { body: jointDetail() });

    const user = userEvent.setup();
    renderScreens('/');
    await screen.findByText('출장비 지출결의서');

    const approveIn = async (title: string): Promise<HTMLElement> =>
      within(await rowFor(title)).getByRole('button', { name: '승인' });

    await user.click(await approveIn('출장비 지출결의서'));
    await waitFor(() => {
      expect(requestsTo('POST /approvals/doc-expense/steps/step-1/approve')).toHaveLength(1);
    });

    // The same decision, pressed again after it failed.
    await user.click(await approveIn('출장비 지출결의서'));
    await waitFor(() => {
      expect(requestsTo('POST /approvals/doc-expense/steps/step-1/approve')).toHaveLength(2);
    });

    const attempts = requestsTo('POST /approvals/doc-expense/steps/step-1/approve');
    const first = attempts[0]?.headers['idempotency-key'];
    const second = attempts[1]?.headers['idempotency-key'];
    expect(first).toBeDefined();
    expect(second).toBe(first);

    await user.click(await approveIn('취업규칙 개정안'));
    await waitFor(() => {
      expect(requestsTo('POST /approvals/doc-joint/steps/step-rep/approve')).toHaveLength(1);
    });
    const other = requestsTo('POST /approvals/doc-joint/steps/step-rep/approve')[0]?.headers[
      'idempotency-key'
    ];
    expect(other).toBeDefined();
    expect(other).not.toBe(first);
  });
});

describe('a row', () => {
  it('carries what somebody needs to decide without opening it', async () => {
    stubInbox();
    renderScreens('/');

    const row = within(await rowFor('출장비 지출결의서'));
    expect(row.getByText('EXPENSE_CLAIM')).toBeDefined();
    expect(row.getByText('5,000,000')).toBeDefined();
    expect(row.getByText('KRW')).toBeDefined();
    // Submitted on the 15th; the fixture clock is later, so it has been waiting.
    expect(row.getByText(/일 경과$/)).toBeDefined();
  });

  it('keeps the exact stored amount reachable when the display rounds it', async () => {
    stubInbox();
    const user = userEvent.setup();
    renderScreens('/');

    await screen.findByText('출장비 지출결의서');
    await user.click(tab('내가 상신함'));
    const row = within(await rowFor(MINE.title ?? ''));

    expect(row.getByText('1,400,000')).toBeDefined();
    await user.click(row.getByRole('button', { name: /1,400,000\.25/ }));
    expect(row.getByText('1,400,000.25')).toBeDefined();
  });
});

describe('a document that is not this account’s to sign', () => {
  it('says so rather than sending an approval nobody asked for', async () => {
    stubInbox(INBOX, [
      'doc-joint',
      detailFor(JOINT, {
        awaitingMe: false,
        steps: [
          {
            id: 'step-other',
            position: 0,
            kind: 'CONCURRENCE',
            state: 'PENDING',
            pending: true,
            requiredApprovals: 1,
            approvalsGiven: 0,
            approvers: [{ accountId: 'acc-someone-else', displayName: '최차장', acted: false }],
          },
        ],
      }),
    ]);
    const user = userEvent.setup();
    renderScreens('/');

    await screen.findByText('취업규칙 개정안');
    await user.click(within(await rowFor('취업규칙 개정안')).getByRole('button', { name: '승인' }));

    expect(await screen.findByText('이 문서에서 지금 결재하실 단계가 없습니다')).toBeDefined();
    expect(requestsTo('POST /approvals/doc-joint/steps/step-other/approve')).toHaveLength(0);
  });
});
