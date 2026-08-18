import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMock = vi.hoisted(() => {
  const fn = vi.fn();
  (globalThis as unknown as { fetch: unknown }).fetch = fn;
  return fn;
});

import { TemplateScreen } from '../../src/screens/documents/TemplateScreen.js';
import { fileNamed, json, problem, renderScreen, routeFetch, type RouteTable } from './support.js';

const TEMPLATE = {
  id: 't-1',
  companyId: 'c-1',
  code: 'EXP-001',
  documentType: '지출결의서',
  nameKo: '지출결의서',
  nameEn: 'Expense request',
  currentVersionNo: 1,
  builtIn: true,
  active: true,
};

const MANIFEST = [
  { tag: 'amount', type: 'MONEY', labelKo: '금액', labelEn: 'Amount', required: true },
  { tag: 'reason', type: 'MULTILINE_TEXT', labelKo: '사유', labelEn: 'Reason', required: false },
];

function routes(extra: RouteTable = {}): RouteTable {
  return {
    'GET /templates/t-1': () => json(TEMPLATE, { etag: 'W/"t-1:1"' }),
    'GET /templates/t-1/versions': () =>
      json([
        {
          templateId: 't-1',
          versionNo: 1,
          hasApprovalBlock: true,
          publishedAt: '2026-08-01T09:00:00.000',
          publishedByAccountId: 'acc-1',
        },
      ]),
    'GET /templates/t-1/versions/1/fields': () => json(MANIFEST),
    'GET /templates/t-1/versions/1/documents': () => json({ items: [], nextCursor: null }),
    ...extra,
  };
}

describe('publishing a template version', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('starts from the manifest that is already published', async () => {
    routeFetch(fetchMock, routes());
    renderScreen(<TemplateScreen templateId="t-1" onOpenDocument={() => undefined} />);

    const tags = await screen.findAllByLabelText('태그');
    expect(tags.map((input) => (input as HTMLInputElement).value)).toEqual(['amount', 'reason']);
  });

  it('sends the manifest and the body, with the ETag it read', async () => {
    const user = userEvent.setup();
    const calls = routeFetch(
      fetchMock,
      routes({
        'POST /templates/t-1/versions': () =>
          json({ templateId: 't-1', versionNo: 2, hasApprovalBlock: true }, { status: 201 }),
      }),
    );
    renderScreen(<TemplateScreen templateId="t-1" onOpenDocument={() => undefined} />);

    await screen.findAllByLabelText('태그');
    await user.upload(
      screen.getAllByLabelText('본문 파일')[0] as HTMLInputElement,
      fileNamed('expense.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    );
    await user.click(screen.getByRole('button', { name: '발행' }));

    await waitFor(() => {
      expect(calls.some((call) => call.method === 'POST')).toBe(true);
    });
    const publish = calls.find((call) => call.method === 'POST');
    expect(publish?.url.searchParams.get('locales')).toBe('ko');
    expect(publish?.url.searchParams.get('formats')).toBe('DOCX');
    expect(JSON.parse(publish?.url.searchParams.get('schema') ?? '{}')).toEqual({
      fields: [
        { tag: 'amount', type: 'MONEY', labelKo: '금액', labelEn: 'Amount', required: true },
        { tag: 'reason', type: 'MULTILINE_TEXT', labelKo: '사유', labelEn: 'Reason', required: false },
      ],
    });
    expect(new Headers(publish?.init?.headers).get('if-match')).toBe('W/"t-1:1"');
    expect(await screen.findByText('2 번 버전을 발행했습니다')).toBeDefined();
  });

  it('puts a cross-validation failure on the field the server named, not in a banner', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /templates/t-1/versions': () =>
          problem(422, 'schema_mismatch', 'The field manifest and the document disagree', [
            {
              field: 'amount',
              code: 'field_not_in_document',
              message:
                'The manifest declares "amount" but the document has no content control with that tag, so the field would render an input that saves into nothing.',
            },
          ]),
      }),
    );
    renderScreen(<TemplateScreen templateId="t-1" onOpenDocument={() => undefined} />);

    await screen.findAllByLabelText('태그');
    await user.upload(
      screen.getAllByLabelText('본문 파일')[0] as HTMLInputElement,
      fileNamed('expense.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    );
    await user.click(screen.getByRole('button', { name: '발행' }));

    const named = await screen.findByDisplayValue('amount');
    await waitFor(() => {
      expect(named.getAttribute('aria-invalid')).toBe('true');
    });

    // The message is attached to the field, reachable from it, and says what to fix.
    const describedBy = named.getAttribute('aria-describedby') ?? '';
    const message = document.getElementById(describedBy.split(' ').at(-1) ?? '');
    expect(message?.textContent).toContain('no content control with that tag');

    // The other field is untouched: one violation, one field.
    const other = screen.getByDisplayValue('reason');
    expect(other.getAttribute('aria-invalid')).toBeNull();
  });

  it('offers to declare a control the document has and the manifest does not', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /templates/t-1/versions': () =>
          problem(422, 'schema_mismatch', 'The field manifest and the document disagree', [
            {
              field: 'approver',
              code: 'control_not_declared',
              message:
                'The document has a content control tagged "approver" that the manifest does not declare, so nobody can fill it in and it will print blank.',
            },
          ]),
      }),
    );
    renderScreen(<TemplateScreen templateId="t-1" onOpenDocument={() => undefined} />);

    await screen.findAllByLabelText('태그');
    await user.upload(
      screen.getAllByLabelText('본문 파일')[0] as HTMLInputElement,
      fileNamed('expense.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    );
    await user.click(screen.getByRole('button', { name: '발행' }));

    const undeclared = await screen.findByText(/nobody can fill it in/);
    const row = undeclared.closest('div');
    expect(row).not.toBeNull();
    await user.click(within(row as HTMLElement).getByRole('button', { name: '항목 추가' }));

    const tags = screen.getAllByLabelText('태그').map((input) => (input as HTMLInputElement).value);
    expect(tags).toEqual(['amount', 'reason', 'approver']);
  });
});

describe('a seeded template', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('can be restored to factory state, and says what that does to history', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({ 'POST /templates/t-1/restore-to-factory': () => json({ templateId: 't-1', versionNo: 3 }) }),
    );
    renderScreen(<TemplateScreen templateId="t-1" onOpenDocument={() => undefined} />);

    expect(
      await screen.findByText(/이력을 지우지 않으므로 예전 문서는 승인 당시 모습 그대로 남습니다/),
    ).toBeDefined();
    await user.click(screen.getByRole('button', { name: '되돌리기' }));

    expect(await screen.findByText('기본 상태를 3 번 버전으로 발행했습니다')).toBeDefined();
  });

  it('refuses to offer restore on a fork, and says why', async () => {
    routeFetch(
      fetchMock,
      routes({ 'GET /templates/t-1': () => json({ ...TEMPLATE, builtIn: false }, { etag: 'W/"t-1:1"' }) }),
    );
    renderScreen(<TemplateScreen templateId="t-1" onOpenDocument={() => undefined} />);

    expect(
      await screen.findByText('기본 제공 서식만 되돌릴 수 있습니다. 복제본에는 되돌릴 기본 상태가 없습니다'),
    ).toBeDefined();
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull();
  });
});
