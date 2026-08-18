import { createI18n, isLocale, type Locale } from '@coreintra/i18n';
import { FullDecimalProvider } from '@coreintra/ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useMemo, type ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';

/**
 * Chooses the starting locale.
 *
 * The server sends the signed-in person's preference, but that arrives after
 * the first paint, so the initial choice comes from what the browser last saw.
 * Korean wins any tie: it is the default locale on a fresh install, and an
 * English flash on a Korean user's screen is the "English-first retrofit" the
 * brief rules out.
 */
function initialLocale(): Locale {
  try {
    const stored = globalThis.localStorage?.getItem('coreintra.locale');
    if (isLocale(stored)) {
      return stored;
    }
  } catch {
    // A locked-down browser policy is not a reason to fail to start.
  }
  return globalThis.navigator?.language?.startsWith('en') === true ? 'en' : 'ko';
}

function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // This is an intranet on one box on the same LAN. Refetching on every
        // window focus is free enough to be worth the freshness on the two
        // screens people leave open all day — the inbox and who's-in.
        refetchOnWindowFocus: true,
        staleTime: 15_000,
        retry: (failureCount, error) => {
          // A 401, 403 or 422 will fail again identically; retrying them only
          // delays the message that explains what to do.
          const status = (error as { status?: number }).status;
          if (typeof status === 'number' && status >= 400 && status < 500) {
            return false;
          }
          return failureCount < 2;
        },
      },
      mutations: {
        // Never retried automatically. A retried POST that creates money or an
        // approval is exactly what the idempotency keys exist to survive, and
        // the client should not lean on them by accident.
        retry: false,
      },
    },
  });
}

export function AppProviders({ children }: { readonly children: ReactNode }): ReactNode {
  const i18n = useMemo(() => createI18n(initialLocale()), []);
  const queryClient = useMemo(createQueryClient, []);

  return (
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <FullDecimalProvider>{children}</FullDecimalProvider>
      </QueryClientProvider>
    </I18nextProvider>
  );
}
