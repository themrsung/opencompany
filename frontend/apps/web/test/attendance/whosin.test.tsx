import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { page, resetApi, route } from '../support/api.js';
import { BOARD, stubBoard } from '../support/fixtures.js';
import { renderScreens } from '../support/render.js';

/**
 * The who's-in board.
 *
 * The two behaviours tested here are the ones people will judge the product on
 * within a week of installing it: what happens to a shift that ran past
 * midnight, and what a colleague can see of a status somebody chose not to
 * share.
 */

async function rowFor(name: string): Promise<HTMLTableRowElement> {
  const cell = await screen.findByText(name);
  const row = cell.closest('tr');
  if (row === null) {
    throw new Error(`no row for ${name}`);
  }
  return row;
}

function board(): HTMLElement {
  return screen.getByRole('table', { name: /근무 현황/ });
}

beforeEach(() => {
  resetApi();
});

afterEach(() => {
  resetApi();
});

describe('a shift that crossed midnight', () => {
  it('reads as 27:00 on the business day it began, not as 03:00 on the next one', async () => {
    stubBoard();
    renderScreens('/whos-in');

    const row = within(await rowFor('김주간'));

    expect(row.getByText('2026-08-30 18:00')).toBeDefined();
    expect(row.getByText('2026-08-30 27:00')).toBeDefined();
    expect(row.queryByText(/2026-08-31/)).toBeNull();
    expect(row.queryByText(/03:00/)).toBeNull();
  });

  it('says in words what 27:00 means, on the day it is opened', async () => {
    stubBoard();
    route('GET /attendance/records', {
      body: page([
        {
          id: 'rec-1',
          employeeId: 'emp-1',
          statusTypeId: 'st-working',
          businessDate: '2026-08-30',
          startedAt: '2026-08-30T18:00:00.000',
          endedAt: '2026-08-30T27:00:00.000',
          durationSeconds: 32_400,
          open: false,
        },
      ]),
    });

    const user = userEvent.setup();
    renderScreens('/whos-in');
    await user.click(await rowFor('김주간'));

    expect(
      await screen.findByText('자정을 넘긴 근무입니다. 2026-08-30 업무일의 27:00 으로 기록됩니다'),
    ).toBeDefined();
    // Nine hours, not minus fifteen.
    expect(screen.getByText('9시간 0분')).toBeDefined();
  });
});

describe('a status that is not visible to peers', () => {
  it('is absent from the board, and the person is not', async () => {
    stubBoard();
    renderScreens('/whos-in');

    const row = within(await rowFor('박비밀'));

    // The row is there — leaving a hole where somebody should be says as much
    // about them as showing the status would.
    expect(row.getByText('비공개')).toBeDefined();

    // Nothing about what they are actually doing reaches the board.
    expect(within(board()).queryByText('재택')).toBeNull();
    expect(row.queryByText(/09:30/)).toBeNull();
  });

  it('is told apart from somebody who has recorded nothing', async () => {
    stubBoard([
      ...BOARD,
      { employeeId: 'emp-4', businessDate: '2026-08-30', visible: true },
    ]);
    route('GET /org/employees', {
      body: page([
        { id: 'emp-3', companyId: 'co-1', nameKo: '박비밀' },
        { id: 'emp-4', companyId: 'co-1', nameKo: '최미기록' },
      ]),
    });

    renderScreens('/whos-in');

    expect(within(await rowFor('최미기록')).getByText('기록 없음')).toBeDefined();
    expect(within(await rowFor('박비밀')).getByText('비공개')).toBeDefined();
  });
});

describe('statuses are data', () => {
  it('renders one the installation invented, not just the six built in', async () => {
    stubBoard();
    renderScreens('/whos-in');

    expect(within(await rowFor('이야근')).getByText('교육')).toBeDefined();
  });

  it('says what each status does, including the flags that change what it costs', async () => {
    stubBoard();
    renderScreens('/whos-in');

    const legend = within(
      (await screen.findByText('이 회사가 쓰는 상태')).closest('section') ?? document.body,
    );

    expect(legend.getByText('Training')).toBeDefined();
    expect(legend.getByText('결재 필요')).toBeDefined();
    expect(legend.getByText('8시간 뒤 자동 종료')).toBeDefined();
  });
});

describe('the board as a whole', () => {
  it('counts who is actually working, and counts everybody present', async () => {
    stubBoard();
    renderScreens('/whos-in');

    // Three people on the board; one of them is mid-shift in a status that
    // counts as working. The private row is a person, so it is in the total.
    expect(await screen.findByText('3명 가운데 1명이 근무 중입니다')).toBeDefined();
  });
});
