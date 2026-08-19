// Must come first: it installs the fetch stub, and the screens imported below
// reach the shared ApiClient, which binds `globalThis.fetch` as it is created.
import './api.js';

import { createI18n, type Locale } from '@coreintra/i18n';
import { FullDecimalProvider } from '@coreintra/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';
import { render, type RenderResult } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { approvalRoutes } from '../../src/screens/approvals/routes.js';
import { attendanceRoutes } from '../../src/screens/attendance/routes.js';

/**
 * The two areas, mounted the way the app mounts them.
 *
 * A real router rather than a bare component render: Enter on an inbox row
 * navigates, and a test that stubbed the navigation away would not notice if it
 * went nowhere. The providers are the app's own — one query client per render
 * so nothing leaks between tests, and Korean, because Korean is the default
 * locale and the copy these screens are judged on is the Korean copy.
 */
function Area(): React.ReactNode {
  return <Outlet />;
}

export function renderScreens(path: string, locale: Locale = 'ko'): RenderResult {
  const root = createRootRoute({ component: Area });
  const tree = root.addChildren([...approvalRoutes(root), ...attendanceRoutes(root)]);
  const router = createRouter({
    routeTree: tree,
    history: createMemoryHistory({ initialEntries: [path] }),
  });

  const queryClient = new QueryClient({
    defaultOptions: {
      // No retries in tests: a screen that only works on the second attempt is
      // a screen that does not work, and the app's own mutation policy is
      // `retry: false` anyway.
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <I18nextProvider i18n={createI18n(locale)}>
      <QueryClientProvider client={queryClient}>
        <FullDecimalProvider>
          <RouterProvider router={router} />
        </FullDecimalProvider>
      </QueryClientProvider>
    </I18nextProvider>,
  );
}
