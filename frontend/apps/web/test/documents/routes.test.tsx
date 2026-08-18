import { createI18n } from '@coreintra/i18n';
import { FullDecimalProvider } from '@coreintra/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMock = vi.hoisted(() => {
  const fn = vi.fn();
  (globalThis as unknown as { fetch: unknown }).fetch = fn;
  return fn;
});

import { documentRoutes } from '../../src/screens/documents/routes.js';
import { json, routeFetch, LICENCE, type RouteTable } from './support.js';

/**
 * The five routes, mounted on a throwaway root.
 *
 * A route tree that does not compose is a blank page in production and nothing
 * at all in a component test, so this is the one test that renders through the
 * router. The root is local rather than the app's own so that a half-finished
 * screen in another area cannot fail this file.
 */
function mountAt(path: string): void {
  const root = createRootRoute({ component: () => <Outlet /> });
  const tree = root.addChildren(documentRoutes(root));
  const router = createRouter({
    routeTree: tree,
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });

  render(
    <I18nextProvider i18n={createI18n('ko')}>
      <QueryClientProvider client={queryClient}>
        <FullDecimalProvider>
          <RouterProvider router={router} />
        </FullDecimalProvider>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

function routes(extra: RouteTable = {}): RouteTable {
  return {
    'GET /org/companies': () =>
      json({ items: [{ id: 'c-1', nameKo: '주식회사 코어', nameEn: 'Core Inc' }], nextCursor: null }),
    ...extra,
  };
}

describe('the document area routes', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('lists documents at /documents, scoped to the chosen company', async () => {
    routeFetch(
      fetchMock,
      routes({
        'GET /documents': (call) => {
          expect(call.url.searchParams.get('companyId')).toBe('c-1');
          return json({
            items: [{ id: 'd-1', title: '8월 지출결의서', documentType: '지출결의서', currentVersionNo: 1 }],
            nextCursor: null,
          });
        },
        'GET /templates': () => json({ items: [], nextCursor: null }),
      }),
    );
    mountAt('/documents');

    expect(await screen.findByText('8월 지출결의서')).toBeDefined();
  });

  it('opens the font manager at /fonts', async () => {
    routeFetch(
      fetchMock,
      routes({
        'GET /fonts': () => json({ items: [], nextCursor: null }),
        'GET /fonts/licence-acknowledgement': () => json(LICENCE),
        'GET /fonts/substitutions': () => json({ familyChains: {}, scriptChains: {} }),
      }),
    );
    mountAt('/fonts');

    expect(await screen.findByRole('heading', { name: '글꼴 관리' })).toBeDefined();
    expect(await screen.findByText('설치된 글꼴이 없습니다')).toBeDefined();
  });

  it('opens one template at /templates/$templateId', async () => {
    routeFetch(
      fetchMock,
      routes({
        'GET /templates/t-9': () =>
          json(
            { id: 't-9', code: 'LEAVE-001', nameKo: '휴가신청서', builtIn: false, active: true },
            { etag: 'W/"t-9:0"' },
          ),
        'GET /templates/t-9/versions': () => json([]),
      }),
    );
    mountAt('/templates/t-9');

    expect(await screen.findByText(/휴가신청서/)).toBeDefined();
    // Said in the header and again as the empty state of the version table:
    // a template with no version drafts nothing, and both places have to say so.
    expect((await screen.findAllByText(/아직 발행한 버전이 없습니다/)).length).toBe(2);
  });
});
