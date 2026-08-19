import { createI18n } from '@coreintra/i18n';
import { FullDecimalProvider } from '@coreintra/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import type { ReactNode } from 'react';
import type { Mock } from 'vitest';

/**
 * The harness for the document, template and font screens.
 *
 * Mocked at the `fetch` boundary, never by stubbing the client: the single
 * client's refresh, its ETag plumbing and its problem+json parsing are part of
 * what these screens depend on, and a stubbed client would test a different
 * program.
 *
 * The mock has to be installed *before* `apps/web/src/api/client.ts` is
 * evaluated, because the client binds `globalThis.fetch` in its constructor and
 * that module is a singleton created at import time. Each test file therefore
 * opens with
 *
 * ```ts
 * const fetchMock = vi.hoisted(() => {
 *   const fn = vi.fn();
 *   (globalThis as unknown as { fetch: unknown }).fetch = fn;
 *   return fn;
 * });
 * ```
 *
 * which `vitest` hoists above the imports. It is one line per file rather than
 * a helper here because the hoisting is what makes it work.
 */

export interface Call {
  readonly method: string;
  readonly url: URL;
  readonly init: RequestInit | undefined;
}

export type Handler = (call: Call) => Response | Promise<Response>;

/** `"GET /fonts"` — method and path, with the `/api/v1` prefix left off. */
export type RouteTable = Readonly<Record<string, Handler>>;

export function routeFetch(fetchMock: Mock, routes: RouteTable): Call[] {
  const calls: Call[] = [];
  fetchMock.mockImplementation((input: unknown, init: RequestInit | undefined) => {
    const url = new URL(String(input), 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({ method, url, init });

    const key = `${method} ${url.pathname.replace(/^\/api\/v1/, '')}`;
    const handler = routes[key];
    if (handler === undefined) {
      // Loud rather than a convincing empty page: an unrouted call is a test
      // that is no longer describing the screen it claims to.
      throw new Error(`no route for ${key} — routed: ${Object.keys(routes).join(', ')}`);
    }
    return handler({ method, url, init });
  });
  return calls;
}

export function json(body: unknown, init: { status?: number; etag?: string } = {}): Response {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (init.etag !== undefined) {
    headers['etag'] = init.etag;
  }
  return new Response(JSON.stringify(body), { status: init.status ?? 200, headers });
}

export function problem(
  status: number,
  code: string,
  detail: string,
  violations: readonly { readonly field: string; readonly code: string; readonly message: string }[] = [],
): Response {
  return new Response(
    JSON.stringify({
      type: `https://coreintra.invalid/problems/${code}`,
      title: code,
      status,
      code,
      detail,
      violations,
    }),
    { status, headers: { 'content-type': 'application/problem+json' } },
  );
}

/** Answers differently on each call, for a job that finishes on the second poll. */
export function inTurn(...responses: readonly Handler[]): Handler {
  let index = 0;
  return (call) => {
    const handler = responses[Math.min(index, responses.length - 1)];
    index += 1;
    if (handler === undefined) {
      throw new Error('inTurn needs at least one response');
    }
    return handler(call);
  };
}

export function renderScreen(node: ReactNode): RenderResult {
  const queryClient = new QueryClient({
    defaultOptions: {
      // Retries would turn a deliberate 409 into three of them and a slow test.
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  const i18n = createI18n('ko');

  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <FullDecimalProvider>{node}</FullDecimalProvider>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

/** A file the browser would produce from a picker. */
export function fileNamed(name: string, type: string, contents = 'bytes'): File {
  return new File([contents], name, { type });
}

export const LICENCE = {
  version: '2026-08-fonts-1',
  textKo:
    '이 글꼴을 설치하고 문서에 포함(embedding)할 권리를 보유하고 있음을 확인합니다. 당사는 글꼴 라이선스를 확인하지 않으며 이에 대해 어떠한 보증이나 면책도 제공하지 않습니다. 글꼴 자체에 기록된 포함 제한(fsType)을 준수할 책임은 전적으로 설치자에게 있습니다.',
  textEn:
    "I confirm that this organisation holds the rights to install this font and to embed it in documents. We do not verify font licences and provide no warranty or indemnity for them. Honouring the embedding restrictions recorded in the font's own metadata (fsType) is entirely the installer's responsibility.",
} as const;
