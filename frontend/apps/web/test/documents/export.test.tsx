import { screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMock = vi.hoisted(() => {
  const fn = vi.fn();
  (globalThis as unknown as { fetch: unknown }).fetch = fn;
  return fn;
});

import { ExportPanel } from '../../src/screens/documents/ExportPanel.js';
import { json, problem, renderScreen, routeFetch, type RouteTable } from './support.js';

/** The pair a DOCX → PDF export asks about, as the matrix answers it. */
const PDF_ROW = {
  fromFormat: 'docx',
  fromDisplayName: 'Word (DOCX)',
  toFormat: 'pdf',
  toDisplayName: 'PDF',
  available: false,
  supported: false,
  unavailableReason:
    'PDF is an export target only: nothing in this system reads it back, so there is no round-trip to describe.',
  features: [],
  concerns: [],
};

const HWP_ROW = {
  fromFormat: 'docx',
  fromDisplayName: 'Word (DOCX)',
  toFormat: 'hwp',
  toDisplayName: '한글 (HWP)',
  available: true,
  supported: true,
  note: '한글 5.0 이진 형식으로 씁니다.',
  features: [
    { feature: 'TABLES', support: 'FULL', describeKo: '표 유지됩니다', describeEn: 'tables survive' },
    {
      feature: 'TRACKED_CHANGES',
      support: 'DROPPED',
      describeKo: '변경 내용 추적 사라집니다',
      describeEn: 'tracked changes is dropped',
    },
  ],
  concerns: [
    {
      feature: 'TRACKED_CHANGES',
      support: 'DROPPED',
      describeKo: '변경 내용 추적 사라집니다',
      describeEn: 'tracked changes is dropped',
    },
  ],
};

function routes(extra: RouteTable = {}): RouteTable {
  return {
    'GET /documents/fidelity': (call) =>
      json(call.url.searchParams.get('to') === 'hwp' ? HWP_ROW : PDF_ROW),
    ...extra,
  };
}

function panel(): ReactNode {
  return <ExportPanel documentId="d-1" storedFormat="DOCX" currentVersionNo={3} />;
}

describe('the fidelity row', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('is on screen for the chosen pair before the export runs', async () => {
    const calls = routeFetch(fetchMock, routes());
    renderScreen(panel());

    expect(await screen.findByText('내보내기를 실행하기 전에 확인해 주십시오 · Word (DOCX) → PDF')).toBeDefined();
    // Read, and nothing exported to produce it.
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
    expect(calls[0]?.url.pathname).toBe('/api/v1/documents/fidelity');
  });

  it('says there is no pair to describe rather than showing an empty table that reads as "no losses"', async () => {
    routeFetch(fetchMock, routes());
    renderScreen(panel());

    expect(await screen.findByText('이 변환에 대해서는 손실 여부를 설명할 수 없습니다')).toBeDefined();
    expect(screen.getByText(/PDF is an export target only/)).toBeDefined();
  });

  it('names the feature that is dropped when the pair is described', async () => {
    const user = userEvent.setup();
    routeFetch(fetchMock, routes());
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.selectOptions(screen.getByLabelText('내보낼 형식'), 'HWP');

    expect(await screen.findByText('특히 주의할 항목')).toBeDefined();
    // Once under "worth knowing", once in the full feature list underneath.
    expect(screen.getAllByText(/변경 내용 추적 사라집니다/).length).toBe(2);
    expect(screen.getAllByText('DROPPED').length).toBe(2);
  });

  it('labels .doc legacy and lossy', async () => {
    const user = userEvent.setup();
    routeFetch(fetchMock, routes());
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.selectOptions(screen.getByLabelText('내보낼 형식'), 'DOC');

    expect(await screen.findByText('.doc 는 레거시 형식입니다')).toBeDefined();
    expect(screen.getByText('레거시 형식이며 일부 서식이 손실됩니다')).toBeDefined();
  });
});

describe('running the export', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('says the bytes were served without conversion, and offers them', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          json({
            documentId: 'd-1',
            versionNo: 3,
            format: 'DOCX',
            status: 'ready',
            mode: 'immediate',
            contentUrl: '/api/v1/documents/d-1/versions/3/content',
            outputSha256: 'b'.repeat(64),
          }),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    expect(await screen.findByText('변환 없이 저장된 파일을 그대로 내려받습니다')).toBeDefined();
    const link = screen.getByRole('link', { name: '내려받기' });
    expect(link.getAttribute('href')).toBe('/api/v1/documents/d-1/versions/3/content');
  });

  it('shows the job and its state when the answer is a queue rather than a file', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          json(
            {
              documentId: 'd-1',
              versionNo: 3,
              format: 'PDF',
              status: 'queued',
              mode: 'asynchronous',
              jobId: 'job-77',
              jobState: 'QUEUED',
              attempt: 0,
              maxAttempts: 3,
              pollUrl: '/api/v1/documents/d-1/export/jobs/job-77',
            },
            { status: 202 },
          ),
        'GET /documents/d-1/export/jobs/job-77': () =>
          json(
            {
              documentId: 'd-1',
              status: 'queued',
              mode: 'asynchronous',
              jobId: 'job-77',
              jobState: 'RUNNING',
              attempt: 1,
              maxAttempts: 3,
            },
            { status: 202 },
          ),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    expect(await screen.findByText('변환 작업을 큐에 넣었습니다. 끝나면 알려 드립니다')).toBeDefined();
    expect(screen.getByText('job-77')).toBeDefined();
    expect(await screen.findByText('변환 중')).toBeDefined();
    expect(screen.getByText('변환이 끝나기를 기다리고 있습니다')).toBeDefined();
    // Nothing to download while it is still a job.
    expect(screen.queryByRole('link', { name: '내려받기' })).toBeNull();
  });

  it('offers the file once the job finishes', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          json(
            { documentId: 'd-1', status: 'queued', mode: 'asynchronous', jobId: 'job-78', jobState: 'QUEUED' },
            { status: 202 },
          ),
        'GET /documents/d-1/export/jobs/job-78': () =>
          json({
            documentId: 'd-1',
            versionNo: 3,
            status: 'ready',
            mode: 'asynchronous',
            jobId: 'job-78',
            jobState: 'SUCCEEDED',
            contentUrl: '/api/v1/documents/d-1/versions/3/export/content?format=PDF',
            renderedAt: '2026-08-18T09:00:00Z',
            rendererVersion: 'libreoffice-24.8',
          }),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    const link = await screen.findByRole('link', { name: '내려받기' });
    expect(link.getAttribute('href')).toBe('/api/v1/documents/d-1/versions/3/export/content?format=PDF');
  });
});

describe('when the conversion worker cannot produce a file', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('says what happened and what to do, and offers no file at all', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          problem(
            409,
            'conversion_failed',
            'Job job-91 used all 3 attempts and stopped with worker_unreachable: connection refused. Nothing partial was written, so there is no file to download. Check that the conversion worker is running and reachable, then ask for the export again.',
          ),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    // The server's own words: they name the job, the attempts and the fix.
    expect(await screen.findByText(/Check that the conversion worker is running and reachable/)).toBeDefined();
    expect(screen.getByText('변환이 파일을 만들지 못했습니다')).toBeDefined();
    expect(screen.getByText('내려받을 파일이 없습니다. 실패한 변환은 파일을 남기지 않습니다')).toBeDefined();
    // Not a broken link, not a disabled one: none.
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByRole('button', { name: '다시 내보내기' })).toBeDefined();
  });

  it('reports the worker being unreachable in words, without a file', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          problem(503, 'conversion_worker_unavailable', 'The conversion worker is not answering.'),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    expect(
      await screen.findByText(
        '문서 변환 서버에 연결할 수 없어 내보내기를 완료하지 못했습니다. 파일은 생성되지 않았습니다',
      ),
    ).toBeDefined();
    expect(screen.queryByRole('link')).toBeNull();
  });
});

describe('a missing font', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('is a named warning: the family asked for and the family used', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          json({
            documentId: 'd-1',
            versionNo: 3,
            status: 'ready',
            mode: 'synchronous',
            contentUrl: '/api/v1/documents/d-1/versions/3/export/content?format=PDF',
            fontWarnings: [
              {
                requestedFamily: '함초롬바탕',
                resolvedFamily: 'NanumMyeongjo',
                substituted: true,
                unresolved: false,
                reason: 'not installed for this company',
              },
            ],
            recordedSubstitutions: ['함초롬바탕 -> NanumMyeongjo'],
          }),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    const warning = await screen.findByText(/함초롬바탕 글꼴이 설치되어 있지 않아 NanumMyeongjo 글꼴로 대체했습니다/);
    expect(warning).toBeDefined();
    expect(screen.getByText('글꼴 경고')).toBeDefined();
    expect(screen.getByText(/함초롬바탕 -> NanumMyeongjo/)).toBeDefined();
  });

  it('says plainly when nothing could stand in for it', async () => {
    const user = userEvent.setup();
    routeFetch(
      fetchMock,
      routes({
        'POST /documents/d-1/export': () =>
          json({
            documentId: 'd-1',
            status: 'ready',
            mode: 'synchronous',
            contentUrl: '/api/v1/documents/d-1/versions/3/export/content?format=PDF',
            fontWarnings: [
              { requestedFamily: 'HY견고딕', resolvedFamily: null, substituted: false, unresolved: true },
            ],
          }),
      }),
    );
    renderScreen(panel());

    await screen.findByText(/Word \(DOCX\) → PDF/);
    await user.click(screen.getByRole('button', { name: '내보내기 실행' }));

    expect(await screen.findByText(/HY견고딕 글꼴을 대신할 글꼴을 찾지 못했습니다/)).toBeDefined();
  });
});
