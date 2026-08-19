import { useQuery } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { approvalKeys, approvals, type Company } from './queries.js';

/**
 * Which company the screen is looking at.
 *
 * Nearly every endpoint these screens call takes `companyId` as a *required*
 * parameter, and nothing in the API says which company the signed-in person
 * belongs to — there is no "who am I" endpoint at all. So the choice is made
 * here: list the companies the caller may read, remember the one they picked,
 * and default to the first. When the API grows a session resource this hook is
 * where it lands.
 *
 * The same hook exists under `screens/attendance` with the same storage key,
 * deliberately duplicated rather than shared: its real home is a session
 * context in `providers.tsx`, which belongs to the app shell rather than to
 * either of these screens. Both copies read the same key, so switching company
 * on one screen carries to the other.
 */
const STORAGE_KEY = 'coreintra.companyId';

function remembered(): string | null {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
  } catch {
    // A browser policy that forbids storage is not a reason to fail to render.
    return null;
  }
}

export interface CompanyChoice {
  readonly companies: readonly Company[];
  readonly companyId: string | null;
  readonly loading: boolean;
  readonly choose: (companyId: string) => void;
}

export function useCompanyChoice(): CompanyChoice {
  const [chosen, setChosen] = useState<string | null>(remembered);
  const query = useQuery({
    queryKey: approvalKeys.companies,
    queryFn: approvals.companies,
    staleTime: 5 * 60_000,
  });

  const choose = useCallback((companyId: string) => {
    setChosen(companyId);
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, companyId);
    } catch {
      // Remembering is a convenience; not remembering is not a failure.
    }
  }, []);

  const companies = query.data ?? [];
  const known = companies.some((company) => company.id === chosen);
  const fallback = companies.find((company) => company.active !== false) ?? companies[0];

  return {
    companies,
    companyId: known && chosen !== null ? chosen : (fallback?.id ?? null),
    loading: query.isPending,
    choose,
  };
}
