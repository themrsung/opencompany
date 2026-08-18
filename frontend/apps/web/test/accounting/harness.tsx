/* The fetch stub has to be evaluated before the API client binds `globalThis.fetch`. */
import './fetchStub.js';

import { createI18n, type Locale } from '@coreintra/i18n';
import { FullDecimalProvider } from '@coreintra/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';
import type { i18n as I18nInstance } from 'i18next';
import { I18nextProvider } from 'react-i18next';

import { accountingRoutes } from '../../src/screens/accounting/routes.js';
import { json, resetApi, route } from './fetchStub.js';

export { calls, callsTo, json, problem, resetApi, route } from './fetchStub.js';
export type { Handler, RecordedCall } from './fetchStub.js';

export interface Mounted extends RenderResult {
  /** For asserting on copy without pasting the catalogue into the test. */
  readonly t: I18nInstance['t'];
}

export function mountAccounting(path: string, locale: Locale = 'ko'): Mounted {
  const i18n = createI18n(locale);

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });

  const root = createRootRoute({ component: () => <Outlet /> });
  const router = createRouter({
    routeTree: root.addChildren(accountingRoutes(root)),
    history: createMemoryHistory({ initialEntries: [path] }),
  });

  const result = render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <FullDecimalProvider initial={false}>
          <RouterProvider router={router} />
        </FullDecimalProvider>
      </QueryClientProvider>
    </I18nextProvider>,
  );

  return Object.assign(result, { t: i18n.t.bind(i18n) });
}

/** One company, one KRW book, a small chart. Enough for any screen to render. */
export function seedBook(): void {
  resetApi();
  try {
    globalThis.localStorage?.clear();
  } catch {
    // jsdom without storage is fine; the provider falls back to the first book.
  }

  route('GET /org/companies', () =>
    json({ items: [{ id: 'c-1', nameKo: '테스트 주식회사', nameEn: 'Test Co', baseCurrencyCode: 'KRW' }], nextCursor: null }),
  );
  route('GET /accounting/books', () => json([{ id: 'b-1', name: '본사 장부', companyId: 'c-1', baseCurrencyCode: 'KRW', retired: false }]));
  route('GET /accounting/books/b-1/currencies', () =>
    json([
      { code: 'KRW', displayDecimals: 0, nameKo: '원', nameEn: 'Won', symbol: '₩' },
      { code: 'USD', displayDecimals: 2, nameKo: '미국 달러', nameEn: 'US dollar', symbol: '$' },
    ]),
  );
  route('GET /accounting/books/b-1/accounts', () =>
    json({
      items: [
        { id: '1000', nameKo: '유동자산', type: 'ASSET', postable: false, retired: false, contra: false },
        {
          id: '1100',
          nameKo: '현금',
          type: 'ASSET',
          parentId: '1000',
          postable: true,
          retired: false,
          contra: false,
          currencyCode: 'KRW',
          category: 'CASH',
        },
        { id: '4100', nameKo: '매출', type: 'INCOME', postable: true, retired: false, contra: false },
      ],
      nextCursor: null,
    }),
  );
  route('GET /accounting/books/b-1/clients', () => json([{ id: 'cl-1', name: '거래처 가', retired: false }]));
}
