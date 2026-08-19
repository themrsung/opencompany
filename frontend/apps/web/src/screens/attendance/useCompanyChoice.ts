import { useQuery } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { attendance, attendanceKeys, type Company } from './queries.js';

/**
 * Which company the screen is looking at.
 *
 * Every endpoint these screens call takes `companyId` as a *required*
 * parameter, and no endpoint says which company the signed-in person belongs
 * to — there is no "who am I" resource at all. So the choice is made here: list
 * the companies the caller may read, remember the one they picked, default to
 * the first.
 *
 * The 결재 screens carry the same hook against the same storage key. That
 * duplication is deliberate: the honest home for this is a session context in
 * the app shell, which belongs to neither of these two areas, and inventing a
 * cross-area import to avoid twenty lines would be the worse trade. Both read
 * the same key, so a choice made on one screen holds on the other.
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
    queryKey: attendanceKeys.companies,
    queryFn: attendance.companies,
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
