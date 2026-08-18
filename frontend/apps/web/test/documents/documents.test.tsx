import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMock = vi.hoisted(() => {
  const fn = vi.fn();
  (globalThis as unknown as { fetch: unknown }).fetch = fn;
  return fn;
});

import { BodyEditor } from '../../src/screens/documents/BodyEditor.js';
import { DocumentScreen } from '../../src/screens/documents/DocumentScreen.js';
import { FieldEntry } from '../../src/screens/documents/FieldEntry.js';
import { VersionHistory } from '../../src/screens/documents/VersionHistory.js';
import { json, renderScreen, routeFetch, type RouteTable } from './support.js';

const DOCUMENT = {
  id: 'd-1',
  companyId: 'c-1',
  title: '8월 지출결의서',
  documentType: '지출결의서',
  templateId: 't-1',
  templateVersionNo: 2,
  currentVersionNo: 2,
  retired: false,
};

const MANIFEST = [
  { tag: 'amount', type: 'MONEY', labelKo: '금액', labelEn: 'Amount', required: true },
  { tag: 'reason', type: 'MULTILINE_TEXT', labelKo: '사유', labelEn: 'Reason', required: true },
  { tag: 'payee', type: 'EMPLOYEE_REF', labelKo: '수령인', labelEn: 'Payee', required: false },
  { tag: 'paidAt', type: 'BUSINESS_INSTANT', labelKo: '지급 시각', labelEn: 'Paid at', required: false },
];

const VALUES = [
  { fieldId: 'amount', type: 'MONEY', labelKo: '금액', amount: '1400000.25', currencyCode: 'KRW', required: true },
  { fieldId: 'paidAt', type: 'BUSINESS_INSTANT', labelKo: '지급 시각', instant: '2026-08-30T26:01:00.000' },
];

function fieldRoutes(extra: RouteTable = {}): RouteTable {
  return {
    'GET /documents/d-1/versions/2/fields': () => json(VALUES),
    'GET /documents/d-1/versions/2/missing-required': () => json(['reason']),
    'GET /templates/t-1/versions/2/fields': () => json(MANIFEST),
    'GET /org/employees': () =>
      json({ items: [{ id: 'e-1', nameKo: '김사원', nameEn: 'Kim', employeeNumber: '2019-004' }] }),
    'GET /org/units': () => json({ items: [{ id: 'u-1', nameKo: '경영지원팀', nameEn: 'Operations' }] }),
    ...extra,
  };
}

describe('typed field entry', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('renders one input per manifest entry, including the ones with no value yet', async () => {
    routeFetch(fetchMock, fieldRoutes());
    renderScreen(<FieldEntry document={DOCUMENT} versionNo={2} companyId="c-1" />);

    expect(await screen.findByLabelText(/금액/)).toBeDefined();
    // `reason` is required and empty: the values response has no row for it, so
    // only a manifest-driven merge puts it on screen at all.
    expect(screen.getByLabelText(/사유/)).toBeDefined();
    expect(screen.getByLabelText(/수령인/)).toBeDefined();
  });

  it('shows the money through <Amount>, with the exact stored value reachable', async () => {
    routeFetch(fetchMock, fieldRoutes());
    renderScreen(<FieldEntry document={DOCUMENT} versionNo={2} companyId="c-1" />);

    await screen.findByLabelText(/금액/);
    // KRW displays at zero decimals; the figure says it is an abbreviation and
    // names the stored value rather than quietly dropping the .25.
    expect(screen.getByText('1,400,000')).toBeDefined();
    expect(screen.getByRole('button', { name: /1,400,000.25/ })).toBeDefined();
  });

  it('names the required fields still empty, as the submission gate does', async () => {
    routeFetch(fetchMock, fieldRoutes());
    renderScreen(<FieldEntry document={DOCUMENT} versionNo={2} companyId="c-1" />);

    expect(await screen.findByText('아직 비어 있는 필수 항목이 1건 있습니다. 상신 전에 채워 주십시오')).toBeDefined();
  });

  it('refuses an amount that is not an exact decimal', async () => {
    const user = userEvent.setup();
    routeFetch(fetchMock, fieldRoutes());
    renderScreen(<FieldEntry document={DOCUMENT} versionNo={2} companyId="c-1" />);

    const amount = await screen.findByLabelText(/금액/);
    await user.clear(amount);
    await user.type(amount, '1.4e6');

    expect(
      screen.getByText('금액은 정확한 숫자여야 합니다. 지수 표기나 반쯤 쓴 숫자는 받지 않습니다'),
    ).toBeDefined();
  });

  it('moves to the next field on Enter and puts a field back on Escape', async () => {
    const user = userEvent.setup();
    routeFetch(fetchMock, fieldRoutes());
    renderScreen(<FieldEntry document={DOCUMENT} versionNo={2} companyId="c-1" />);

    const amount = await screen.findByLabelText(/금액/);
    amount.focus();
    await user.keyboard('{Enter}');
    expect(document.activeElement).not.toBe(amount);

    amount.focus();
    await user.type(amount, '9');
    expect((amount as HTMLInputElement).value).toBe('1400000.259');
    await user.keyboard('{Escape}');
    expect((amount as HTMLInputElement).value).toBe('1400000.25');
  });

  it('says that field values cannot be saved on their own, and what to do instead', async () => {
    routeFetch(fetchMock, fieldRoutes());
    renderScreen(<FieldEntry document={DOCUMENT} versionNo={2} companyId="c-1" />);

    await screen.findByLabelText(/금액/);
    const save = screen.getByRole('button', { name: '저장' });
    expect((save as HTMLButtonElement).disabled).toBe(true);

    const explanation = document.getElementById(save.getAttribute('aria-describedby') ?? '');
    expect(explanation?.textContent).toContain('입력 항목 값만 저장하는 경로가 없습니다');
    expect(screen.getByText(/본문 탭에서 파일을 내려받아 고친 뒤 새 버전으로 올려 주십시오/)).toBeDefined();
  });
});

describe('comparing two versions', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  const versions = [
    { documentId: 'd-1', versionNo: 1, format: 'DOCX', blobSha256: 'a'.repeat(64), authoredAt: '2026-08-01T09:00:00.000' },
    {
      documentId: 'd-1',
      versionNo: 2,
      format: 'DOCX',
      blobSha256: 'b'.repeat(64),
      supersedesVersionNo: 1,
      authoredAt: '2026-08-02T27:00:00.000',
    },
  ];

  it('says what a comparison can and cannot mean for a docx, instead of an empty diff', async () => {
    const user = userEvent.setup();
    routeFetch(fetchMock, {
      'GET /documents/d-1/versions': () => json({ items: versions, nextCursor: null }),
      'GET /documents/d-1/versions/2/diff': () =>
        json({
          documentId: 'd-1',
          fromVersion: 1,
          toVersion: 2,
          fromFormat: 'DOCX',
          toFormat: 'DOCX',
          identicalBytes: false,
          body: {
            available: false,
            reason:
              'A line-by-line comparison is only meaningful for mdv, whose stored form is canonical text.',
            lines: [],
          },
          fields: [
            {
              fieldId: 'amount',
              change: 'CHANGED',
              before: { fieldId: 'amount', labelKo: '금액', amount: '1000000', currencyCode: 'KRW' },
              after: { fieldId: 'amount', labelKo: '금액', amount: '1400000', currencyCode: 'KRW' },
            },
          ],
        }),
    });
    renderScreen(<VersionHistory documentId="d-1" currentVersionNo={2} />);

    await screen.findByText('버전 이력');
    await user.click(screen.getByRole('button', { name: '비교' }));

    expect(await screen.findByText('이 형식에는 줄 단위 비교가 없습니다')).toBeDefined();
    expect(screen.getByText(/저장된 바이트가 같은지 여부뿐입니다/)).toBeDefined();
    // The server's own sentence is kept, not paraphrased away.
    expect(screen.getByText(/only meaningful for mdv/)).toBeDefined();
    expect(screen.getByText('두 버전의 바이트가 다릅니다')).toBeDefined();

    // And the thing that *can* be compared is compared, through <Amount>.
    expect(screen.getByText('1,000,000')).toBeDefined();
    expect(screen.getByText('1,400,000')).toBeDefined();
  });

  const mdvVersions = [
    { documentId: 'd-1', versionNo: 1, format: 'MDV', blobSha256: 'c'.repeat(64) },
    { documentId: 'd-1', versionNo: 2, format: 'MDV', blobSha256: 'd'.repeat(64), supersedesVersionNo: 1 },
  ];

  it('shows a real line diff for mdv, with the counts', async () => {
    const user = userEvent.setup();
    routeFetch(fetchMock, {
      'GET /documents/d-1/versions': () => json({ items: mdvVersions, nextCursor: null }),
      'GET /documents/d-1/versions/2/diff': () =>
        json({
          documentId: 'd-1',
          fromVersion: 1,
          toVersion: 2,
          fromFormat: 'MDV',
          toFormat: 'MDV',
          identicalBytes: false,
          body: {
            available: true,
            addedLines: 1,
            removedLines: 1,
            truncated: false,
            summarised: false,
            lines: [
              { op: 'REMOVED', beforeLine: 3, text: '금액: 1,000,000' },
              { op: 'ADDED', afterLine: 3, text: '금액: 1,400,000' },
              { op: 'CONTEXT', beforeLine: 4, afterLine: 4, text: '사유: 8월 소모품' },
            ],
          },
          fields: [],
        }),
    });
    renderScreen(<VersionHistory documentId="d-1" currentVersionNo={2} />);

    await screen.findByText('버전 이력');
    await user.click(screen.getByRole('button', { name: '비교' }));

    expect(await screen.findByText('1줄 추가, 1줄 삭제')).toBeDefined();
    expect(screen.getByText(/- 금액: 1,000,000/)).toBeDefined();
    expect(screen.getByText(/\+ 금액: 1,400,000/)).toBeDefined();
    expect(screen.getByText('입력 항목 값에는 차이가 없습니다')).toBeDefined();
  });
});

describe('the body', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('edits and saves an mdv body as a new version, with the ETag it read', async () => {
    const user = userEvent.setup();
    const calls = routeFetch(fetchMock, {
      'GET /documents/d-1/versions/2/content': () =>
        new Response('# 지출결의서\n\n금액: 1,400,000\n', {
          status: 200,
          headers: { 'content-type': 'text/vnd.mdv' },
        }),
      'POST /documents/d-1/versions': () => json({ documentId: 'd-1', versionNo: 3, format: 'MDV' }, { status: 201 }),
    });
    renderScreen(
      <BodyEditor
        documentId="d-1"
        versionNo={2}
        format="MDV"
        etag={'W/"d-1:2"'}
        retired={false}
        onSaved={() => undefined}
      />,
    );

    const body = await screen.findByLabelText('본문');
    await waitFor(() => {
      expect((body as HTMLTextAreaElement).value).toContain('금액: 1,400,000');
    });
    await user.type(body, '\n비고: 확인 완료');
    await user.click(screen.getByRole('button', { name: '새 버전으로 저장' }));

    await waitFor(() => {
      expect(calls.some((call) => call.method === 'POST')).toBe(true);
    });
    const save = calls.find((call) => call.method === 'POST');
    expect(save?.url.searchParams.get('format')).toBe('MDV');
    expect(new Headers(save?.init?.headers).get('if-match')).toBe('W/"d-1:2"');
    expect(save?.init?.body instanceof FormData).toBe(true);
    expect(await screen.findByText('버전 3 으로 저장했습니다')).toBeDefined();
  });

  it('does not pretend to edit a docx in the browser, and says what to do instead', async () => {
    routeFetch(fetchMock, {});
    renderScreen(
      <BodyEditor
        documentId="d-1"
        versionNo={2}
        format="DOCX"
        etag={null}
        retired={false}
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByText('이 형식은 브라우저에서 편집하지 않습니다')).toBeDefined();
    expect(screen.getByText(/파일을 내려받아 고친 뒤 새 버전으로 올려 주십시오/)).toBeDefined();
    expect(screen.queryByLabelText('본문')).toBeNull();
    // The stored bytes are still one click away.
    expect(screen.getByRole('link', { name: '현재 버전 내려받기' }).getAttribute('href')).toBe(
      '/api/v1/documents/d-1/versions/2/content',
    );
  });
});

describe('the document screen', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('states that a 도장 is never served, and offers nothing that would serve one', async () => {
    routeFetch(
      fetchMock,
      fieldRoutes({
        'GET /documents/d-1': () => json(DOCUMENT, { etag: 'W/"d-1:2"' }),
        'GET /documents/d-1/versions': () =>
          json({ items: [{ documentId: 'd-1', versionNo: 2, format: 'DOCX' }], nextCursor: null }),
      }),
    );
    renderScreen(<DocumentScreen documentId="d-1" companyId="c-1" />);

    expect(
      await screen.findByText(
        '도장·서명 이미지는 렌더할 때 서버에서만 합성됩니다. 이 화면을 포함해 어디에서도 이미지 파일로 받아볼 수 없습니다',
      ),
    ).toBeDefined();

    for (const link of screen.queryAllByRole('link')) {
      const href = link.getAttribute('href') ?? '';
      expect(/signature|seal|stamp|도장/i.test(href)).toBe(false);
    }
    expect(screen.queryAllByRole('img').length).toBe(0);
  });
});
