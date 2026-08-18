// This import must stay first: it installs the fetch dispatcher before the API
// client singleton binds `globalThis.fetch`. See fetchMock.ts.
import './fetchMock.js';

import { createI18n, type Locale } from '@coreintra/i18n';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import { render, type RenderResult } from '@testing-library/react';
import type { ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';
import { SessionProvider, type SignedIn } from '../../src/session/session.js';

export { setRoutes, recordedCalls, callsTo } from './fetchMock.js';
export type { Call, Reply, Route } from './fetchMock.js';

/**
 * Renders one screen with the providers it actually depends on.
 *
 * The screens under test are reached through the real router, the real query
 * client and the real session provider, with only the network replaced — the
 * client's refresh, its problem+json parsing and its idempotency headers are
 * part of what these screens rely on, so stubbing the client instead would
 * test a mock.
 *
 * The route tree is a throwaway: the component under test is the root, and the
 * other paths exist so that `<Link>` has somewhere to point.
 */
const LINK_PATHS = [
  '/',
  '/whos-in',
  '/documents',
  '/templates',
  '/leave',
  '/org',
  '/org/people',
  '/org/explainer',
  '/accounting',
  '/fonts',
  '/audit',
  '/support',
  '/support/report/$grantId',
  '/security',
  '/signin',
] as const;

/** Where the router ended up, for tests about the guard. */
let lastRouter: { state: { location: { pathname: string } } } | null = null;

export function currentPath(): string {
  return lastRouter?.state.location.pathname ?? '';
}

export interface HarnessOptions {
  /** Seeds the remembered profile, the way a previous sign-in would have. */
  readonly identity?: SignedIn;
  readonly locale?: Locale;
  readonly path?: string;
}

export function renderScreen(ui: ReactNode, options: HarnessOptions = {}): RenderResult {
  globalThis.localStorage.clear();
  if (options.identity !== undefined) {
    globalThis.localStorage.setItem('coreintra.profile', JSON.stringify(options.identity));
  }

  const i18n = createI18n(options.locale ?? 'en');
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });

  const rootRoute = createRootRoute({ component: () => <>{ui}</> });
  rootRoute.addChildren(
    LINK_PATHS.map((path) =>
      createRoute({ getParentRoute: () => rootRoute, path, component: () => null }),
    ),
  );
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: [options.path ?? '/'] }),
  });
  lastRouter = router;

  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <SessionProvider>
          <RouterProvider router={router} />
        </SessionProvider>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

/** A signed-in session that exists, with no devices worth listing. */
export const SIGNED_IN_ROUTES = {
  'GET /auth/session/active': { body: [] },
} as const;

export const SOMEONE: SignedIn = {
  accountId: 'acc-1',
  displayName: '김지원',
  employeeId: 'emp-1',
  locale: 'ko',
  master: true,
  remainingRecoveryCodes: 8,
};

export const ONE_COMPANY = {
  items: [
    {
      id: 'co-1',
      nameKo: '주식회사 코어인트라',
      nameEn: 'CoreIntra Inc.',
      code: 'HQ',
      active: true,
    },
  ],
  nextCursor: null,
};
