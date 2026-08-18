import { useQuery } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docKeys, reads } from './api.js';
import { Select } from './controls.js';
import { useLocalName } from './format.js';
import type { CompanyView } from './contract.js';

/**
 * Which company these screens are looking at.
 *
 * Every documents, template and font endpoint takes a `companyId` and there is
 * no session endpoint that says which one the signed-in person belongs to — the
 * active-session read carries the session, not the employment. So the choice is
 * made here, remembered per browser, and shown as a control rather than
 * inferred: a font installed into the wrong company is a support ticket, and a
 * silent default is how that happens.
 *
 * Local to this area on purpose. When a company context lands in the app shell
 * this hook becomes a one-line adapter onto it.
 */

const STORAGE_KEY = 'coreintra.documents.companyId';

function remembered(): string | null {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
  } catch {
    // A locked-down browser policy is not a reason to fail to start.
    return null;
  }
}

function remember(companyId: string): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, companyId);
  } catch {
    // Nothing to do, and nothing worth interrupting anyone over.
  }
}

export interface CompanyChoice {
  readonly companies: readonly CompanyView[];
  readonly companyId: string | null;
  readonly setCompanyId: (companyId: string) => void;
  readonly isPending: boolean;
  readonly error: unknown;
}

export function useCompanyChoice(): CompanyChoice {
  const query = useQuery({ queryKey: docKeys.companies, queryFn: reads.companies });
  const [chosen, setChosen] = useState<string | null>(remembered);

  const companies = query.data ?? [];
  const known = companies.some((company) => company.id === chosen);
  const fallback = companies[0]?.id ?? null;
  const companyId = known ? chosen : fallback;

  useEffect(() => {
    if (companyId !== null && companyId !== chosen) {
      setChosen(companyId);
    }
  }, [companyId, chosen]);

  return {
    companies,
    companyId,
    setCompanyId: (next: string) => {
      remember(next);
      setChosen(next);
    },
    isPending: query.isPending,
    error: query.error,
  };
}

export function CompanyPicker({ choice }: { readonly choice: CompanyChoice }): ReactNode {
  const { t } = useTranslation();
  const localName = useLocalName();

  if (choice.companies.length <= 1) {
    // One company is the common on-prem case. A select with a single option is
    // furniture, not a choice.
    return null;
  }

  return (
    <div style={{ maxWidth: '20rem', marginBottom: 'var(--ci-space-4)' }}>
      <Select
        label={t('docs.companyLabel')}
        value={choice.companyId ?? ''}
        onChange={choice.setCompanyId}
        options={choice.companies.map((company) => ({
          value: company.id ?? '',
          label: localName(company.nameKo, company.nameEn),
        }))}
      />
    </div>
  );
}
