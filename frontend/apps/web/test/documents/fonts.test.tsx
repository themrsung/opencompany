import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMock = vi.hoisted(() => {
  const fn = vi.fn();
  (globalThis as unknown as { fetch: unknown }).fetch = fn;
  return fn;
});

import { FontsScreen } from '../../src/screens/documents/FontsScreen.js';
import { fileNamed, json, LICENCE, renderScreen, routeFetch, type RouteTable } from './support.js';

const FONT = {
  id: 'f-1',
  family: '함초롬바탕',
  style: 'Regular',
  fileFormat: 'TTF',
  scriptCoverage: ['Hang', 'Latn'],
  source: 'CLIENT_UPLOADED',
  uploadedByAccountId: 'acc-7',
  uploadedAt: '2026-08-01T02:00:00Z',
  embeddingPermission: 'RESTRICTED',
  enabled: true,
  blobSha256: 'a'.repeat(64),
  fontconfigFileName: 'hamchorom-bt.ttf',
  webfontFaceCss: "@font-face { font-family: '함초롬바탕'; }",
  mdvFontConfigEntry: 'pdf.fonts.함초롬바탕',
  licenceAcknowledgementText: `[${LICENCE.version}] …`,
};

function baseRoutes(extra: RouteTable = {}): RouteTable {
  return {
    'GET /fonts': () => json({ items: [FONT], nextCursor: null }),
    'GET /fonts/licence-acknowledgement': () => json(LICENCE),
    'GET /fonts/substitutions': () => json({ familyChains: {}, scriptChains: {} }),
    ...extra,
  };
}

describe('installing a client font', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('shows the acknowledgement in full and does not tick it for you', async () => {
    routeFetch(fetchMock, baseRoutes());
    renderScreen(<FontsScreen companyId="c-1" />);

    // The words themselves, not a link to them.
    expect(await screen.findByText(LICENCE.textKo)).toBeDefined();
    expect(screen.getByText(LICENCE.textEn)).toBeDefined();

    const box = screen.getByRole('checkbox', { name: /동의합니다/ });
    expect((box as HTMLInputElement).checked).toBe(false);
  });

  it('refuses to install until the acknowledgement is ticked, and sends the words back when it is', async () => {
    const user = userEvent.setup();
    const calls = routeFetch(
      fetchMock,
      baseRoutes({ 'POST /fonts': () => json({ ...FONT, id: 'f-2' }, { status: 201 }) }),
    );
    renderScreen(<FontsScreen companyId="c-1" />);

    await screen.findByText(LICENCE.textKo);
    await user.type(screen.getByLabelText('글꼴 이름'), 'KoPubWorld돋움');
    await user.upload(screen.getByLabelText('글꼴 파일'), fileNamed('kopub.ttf', 'font/ttf'));

    // Everything else is filled in. The only thing missing is the agreement.
    const install = screen.getByRole('button', { name: '설치' });
    expect((install as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('라이선스 확인에 동의해야 설치할 수 있습니다')).toBeDefined();

    await user.click(install);
    expect(calls.some((call) => call.method === 'POST')).toBe(false);

    await user.click(screen.getByRole('checkbox', { name: /동의합니다/ }));
    expect((screen.getByRole('button', { name: '설치' }) as HTMLButtonElement).disabled).toBe(false);

    await user.click(screen.getByRole('button', { name: '설치' }));

    await waitFor(() => {
      expect(calls.some((call) => call.method === 'POST' && call.url.pathname === '/api/v1/fonts')).toBe(true);
    });
    const post = calls.find((call) => call.method === 'POST');
    expect(post?.url.searchParams.get('licenceAcknowledged')).toBe('true');
    expect(post?.url.searchParams.get('licenceAcknowledgementVersion')).toBe(LICENCE.version);
    // Echoed back exactly: the record is only evidence if it is the text shown.
    expect(post?.url.searchParams.get('licenceAcknowledgementText')).toBe(LICENCE.textKo);
  });

  it('closes the install form when the wording cannot be read, rather than agreeing to nothing', async () => {
    routeFetch(fetchMock, baseRoutes({ 'GET /fonts/licence-acknowledgement': () => json({ nope: true }) }));
    renderScreen(<FontsScreen companyId="c-1" />);

    expect(
      await screen.findByText(/확인 문구를 불러오지 못해 설치를 열 수 없습니다/),
    ).toBeDefined();
    expect(screen.queryByRole('checkbox', { name: /동의합니다/ })).toBeNull();
    expect((screen.getByRole('button', { name: '설치' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('removing a font', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('names how many documents it affects before anything is removed', async () => {
    const user = userEvent.setup();
    const calls = routeFetch(
      fetchMock,
      baseRoutes({
        'GET /fonts/f-1/removal-impact': () =>
          json({
            fontId: 'f-1',
            family: '함초롬바탕',
            affectedRenderCount: 12,
            safe: false,
            warningKo: '이 글꼴로 그려진 보관 렌더가 있습니다.',
            warningEn: 'Archived renders used this font.',
          }),
        'DELETE /fonts/f-1': () => json({ removed: true }),
      }),
    );
    renderScreen(<FontsScreen companyId="c-1" />);

    await user.click(await screen.findByText('함초롬바탕'));
    await user.click(screen.getByRole('button', { name: '삭제' }));

    // The count is on screen, and nothing has been removed to find it out.
    expect(await screen.findByText('이 글꼴을 사용한 보관 렌더가 12건 있습니다')).toBeDefined();
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);

    await user.click(screen.getByRole('button', { name: '12건을 확인했습니다. 삭제합니다' }));

    await waitFor(() => {
      expect(calls.some((call) => call.method === 'DELETE')).toBe(true);
    });
    const removal = calls.find((call) => call.method === 'DELETE');
    // The number the person read is the number the server is told.
    expect(removal?.url.searchParams.get('acknowledgedAffectedCount')).toBe('12');
  });

  it('says so when the count moves while it is being read', async () => {
    const user = userEvent.setup();
    let affected = 12;
    routeFetch(
      fetchMock,
      baseRoutes({
        'GET /fonts/f-1/removal-impact': () =>
          json({ fontId: 'f-1', family: '함초롬바탕', affectedRenderCount: affected, safe: false }),
        'DELETE /fonts/f-1': () => {
          affected = 15;
          return new Response(
            JSON.stringify({
              type: 'about:blank',
              title: 'affected_count_changed',
              status: 409,
              code: 'affected_count_changed',
              detail: 'The count moved from 12 to 15.',
            }),
            { status: 409, headers: { 'content-type': 'application/problem+json' } },
          );
        },
      }),
    );
    renderScreen(<FontsScreen companyId="c-1" />);

    await user.click(await screen.findByText('함초롬바탕'));
    await user.click(screen.getByRole('button', { name: '삭제' }));
    await user.click(await screen.findByRole('button', { name: '12건을 확인했습니다. 삭제합니다' }));

    expect(await screen.findByText(/건수가 15건으로 바뀌었습니다/)).toBeDefined();
  });
});

describe('what the manager will not do', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it("shows the font's own embedding claim as a claim, read-only", async () => {
    routeFetch(fetchMock, baseRoutes());
    renderScreen(<FontsScreen companyId="c-1" />);

    expect(await screen.findByText('내장 금지')).toBeDefined();
    expect(
      screen.getAllByText(/저희는 확인하지 않으며, 이 제한을 지킬 책임은 설치한 쪽에 있습니다/).length,
    ).toBeGreaterThan(0);
  });
});
