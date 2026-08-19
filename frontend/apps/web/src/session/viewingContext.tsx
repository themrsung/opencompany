import type { components } from '@coreintra/api-client';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useCallback, useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client.js';

export type CompanyView = components['schemas']['CompanyView'];
type CompanyPage = components['schemas']['CursorPageCompanyView'];

/**
 * The two coordinates every §3 screen is read through: which company, and on
 * what date. They travel together because a name, a rank and a permission are
 * all answers to "on this date, in this company" — never to "now".
 */

const COMPANY_KEY = 'coreintra.company';

/**
 * Today, as a calendar date, in the reader's own timezone.
 *
 * Not a business instant: this is only the default the date field starts on,
 * and the user is free to move it. Built by hand rather than sliced out of an
 * ISO string, because `toISOString()` is UTC and would hand a Korean user
 * yesterday's org chart for nine hours of every day.
 */
export function todayBusinessDate(): string {
  const now = new Date();
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** The name in the reader's language, falling back to the other one rather than to blank. */
export function localisedName(
  names: { readonly nameKo?: string | undefined; readonly nameEn?: string | undefined },
  language: string,
): string {
  const korean = language.startsWith('ko');
  const first = korean ? names.nameKo : names.nameEn;
  const second = korean ? names.nameEn : names.nameKo;
  return first ?? second ?? '';
}

export function useCompanies(businessDate: string): UseQueryResult<readonly CompanyView[], Error> {
  return useQuery({
    queryKey: ['org', 'companies', businessDate],
    queryFn: async () => {
      const page = await api.get<CompanyPage>('/org/companies', {
        query: { limit: 200, businessDate },
      });
      return page.items ?? [];
    },
  });
}

export interface CompanyChoice {
  readonly companyId: string | null;
  readonly setCompanyId: (id: string) => void;
  readonly companies: readonly CompanyView[];
  readonly loading: boolean;
  readonly error: unknown;
}

/**
 * Which company the reader is looking at, remembered across screens.
 *
 * A preference, not a permission: the server decides what this account may
 * see in the company, every time. Remembering it locally only saves the
 * person from re-picking it on every screen.
 */
export function useCompanyChoice(businessDate: string): CompanyChoice {
  const companies = useCompanies(businessDate);
  const [stored, setStored] = useState<string | null>(() => {
    try {
      return globalThis.localStorage?.getItem(COMPANY_KEY) ?? null;
    } catch {
      return null;
    }
  });

  const setCompanyId = useCallback((id: string) => {
    setStored(id);
    try {
      globalThis.localStorage?.setItem(COMPANY_KEY, id);
    } catch {
      // A locked-down browser policy is not a reason to fail to switch company.
    }
  }, []);

  const items = companies.data ?? [];
  const known = stored !== null && items.some((item) => item.id === stored);
  const companyId = known ? stored : (items[0]?.id ?? null);

  return {
    companyId,
    setCompanyId,
    companies: items,
    loading: companies.isPending,
    error: companies.error,
  };
}

export function CompanySelect({
  choice,
  language,
}: {
  readonly choice: CompanyChoice;
  readonly language: string;
}): ReactNode {
  const { t } = useTranslation();
  const id = useId();
  return (
    <div className="ci-field">
      <label className="ci-field__label" htmlFor={id}>
        {t('common.company')}
      </label>
      <select
        id={id}
        className="ci-field__input"
        value={choice.companyId ?? ''}
        onChange={(event) => {
          choice.setCompanyId(event.target.value);
        }}
      >
        {choice.companies.map((company) => (
          <option key={company.id} value={company.id}>
            {localisedName(company, language)}
          </option>
        ))}
      </select>
    </div>
  );
}

export function AsOfField({
  value,
  onChange,
  label,
  hint,
}: {
  readonly value: string;
  readonly onChange: (next: string) => void;
  readonly label: string;
  readonly hint?: string;
}): ReactNode {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <div className="ci-field">
      <label className="ci-field__label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className="ci-field__input"
        type="date"
        value={value}
        aria-describedby={hint === undefined ? undefined : hintId}
        onChange={(event) => {
          onChange(event.target.value);
        }}
      />
      {hint === undefined ? null : (
        <p className="ci-field__hint" id={hintId}>
          {hint}
        </p>
      )}
    </div>
  );
}
